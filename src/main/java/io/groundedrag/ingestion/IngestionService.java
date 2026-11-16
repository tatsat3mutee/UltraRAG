package io.groundedrag.ingestion;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import io.groundedrag.domain.Chunk;
import io.groundedrag.domain.DocumentRecord;
import io.groundedrag.domain.DocumentStatus;
import io.groundedrag.domain.ParentChunk;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.retrieval.Embedder;
import io.groundedrag.store.KnowledgeStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts an upload, returns immediately with a QUEUED document version, and indexes it on a
 * bounded worker pool. The previously ACTIVE version keeps serving until the new one is fully
 * embedded and stored, then {@link KnowledgeStore#activate} swaps them in one transaction. A
 * failure at any step leaves the old version serving and the new one marked FAILED with a reason.
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    public record Submission(DocumentRecord document, boolean duplicate) {
    }

    private final KnowledgeStore store;
    private final DocumentParser parser;
    private final PiiRedactor redactor;
    private final ParentChildChunker chunker;
    private final Embedder embedder;
    private final Executor executor;
    private final MeterRegistry meters;

    public IngestionService(KnowledgeStore store, DocumentParser parser, PiiRedactor redactor,
            ParentChildChunker chunker, Embedder embedder, Executor executor, MeterRegistry meters) {
        this.store = store;
        this.parser = parser;
        this.redactor = redactor;
        this.chunker = chunker;
        this.embedder = embedder;
        this.executor = executor;
        this.meters = meters;
    }

    public Submission submit(SpaceId space, String filename, String contentType, byte[] bytes) {
        parser.validate(filename);
        String hash = sha256(bytes);
        var existing = store.findActiveByHash(space, hash);
        if (existing.isPresent()) {
            meters.counter("rag.ingest.duplicates").increment();
            return new Submission(existing.get(), true);
        }
        DocumentRecord doc = store.createDocument(space, filename, hash, contentType);
        try {
            executor.execute(() -> index(doc, bytes));
        } catch (RejectedExecutionException e) {
            store.updateStatus(doc.id(), DocumentStatus.FAILED, "Ingestion queue is full, retry later");
            throw new IngestionBusyException();
        }
        return new Submission(doc, false);
    }

    /** Runs synchronously; public so tests and the evaluation harness can ingest deterministically. */
    public void index(DocumentRecord doc, byte[] bytes) {
        Timer.Sample sample = Timer.start(meters);
        store.updateStatus(doc.id(), DocumentStatus.INDEXING, null);
        try {
            List<ParsedSection> sections = parser.parse(doc.sourceName(), bytes).stream()
                    .map(s -> new ParsedSection(redactor.redact(s.text()), s.page(), s.section()))
                    .toList();
            List<ParentChildChunker.Piece> pieces = chunker.chunk(sections);

            List<ParentChunk> parents = new ArrayList<>();
            List<String> childTexts = new ArrayList<>();
            List<String> embedTexts = new ArrayList<>();
            List<UUID> childParent = new ArrayList<>();
            List<ParentChildChunker.Piece> childPiece = new ArrayList<>();
            for (int p = 0; p < pieces.size(); p++) {
                ParentChildChunker.Piece piece = pieces.get(p);
                ParentChunk parent = new ParentChunk(UUID.randomUUID(), doc.id(), doc.space(), p, piece.parentText());
                parents.add(parent);
                for (String child : piece.children()) {
                    childTexts.add(child);
                    embedTexts.add(contextualHeader(doc.sourceName(), piece) + child);
                    childParent.add(parent.id());
                    childPiece.add(piece);
                }
            }

            List<float[]> vectors = embedder.embedAll(embedTexts);
            List<Chunk> chunks = new ArrayList<>(childTexts.size());
            for (int i = 0; i < childTexts.size(); i++) {
                ParentChildChunker.Piece piece = childPiece.get(i);
                chunks.add(new Chunk(UUID.randomUUID(), doc.id(), childParent.get(i), doc.space(), i,
                        childTexts.get(i), doc.sourceName(), piece.page(), piece.section(), vectors.get(i)));
            }
            store.saveChunks(doc.id(), parents, chunks);
            store.activate(doc.id(), embedder.modelName(), chunks.size());
            log.info("Indexed {} v{} in space {}: {} parents, {} chunks", doc.sourceName(), doc.version(),
                    doc.space(), parents.size(), chunks.size());
            sample.stop(meters.timer("rag.ingest", "outcome", "success"));
        } catch (RuntimeException e) {
            log.warn("Ingestion failed for {} v{}: {}", doc.sourceName(), doc.version(), e.toString());
            store.discardChunks(doc.id());
            store.updateStatus(doc.id(), DocumentStatus.FAILED, truncate(e.getMessage()));
            sample.stop(meters.timer("rag.ingest", "outcome", "failure"));
        }
    }

    /**
     * Prepended to the text that is embedded (not to the stored text): a child chunk like
     * "It is 30 days." becomes findable for "refund window" once it carries its section title.
     */
    static String contextualHeader(String sourceName, ParentChildChunker.Piece piece) {
        StringBuilder sb = new StringBuilder(sourceName);
        if (piece.section() != null) {
            sb.append(" > ").append(piece.section());
        }
        return sb.append("\n").toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "Unknown error";
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
