package io.groundedrag.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.groundedrag.domain.Chunk;
import io.groundedrag.domain.DocumentRecord;
import io.groundedrag.domain.DocumentStatus;
import io.groundedrag.domain.ParentChunk;
import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.domain.TextAnalyzer;

/**
 * Zero-infrastructure adapter: exact cosine scan + BM25. Writes are serialised with a single lock
 * so activation is atomic with respect to readers; reads are lock-free over immutable snapshots.
 */
public class InMemoryKnowledgeStore implements KnowledgeStore {

    private final Map<UUID, DocumentRecord> documents = new ConcurrentHashMap<>();
    private final Map<UUID, ParentChunk> parents = new ConcurrentHashMap<>();
    private final Map<UUID, Chunk> chunks = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> chunkTerms = new ConcurrentHashMap<>();
    private final Map<SpaceId, Long> corpusVersions = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    @Override
    public DocumentRecord createDocument(SpaceId space, String sourceName, String contentHash, String contentType) {
        synchronized (writeLock) {
            int version = documents.values().stream()
                    .filter(d -> d.space().equals(space) && d.sourceName().equals(sourceName))
                    .mapToInt(DocumentRecord::version).max().orElse(0) + 1;
            Instant now = Instant.now();
            DocumentRecord doc = new DocumentRecord(UUID.randomUUID(), space, sourceName, contentHash, version,
                    DocumentStatus.QUEUED, contentType, 0, null, null, now, now);
            documents.put(doc.id(), doc);
            return doc;
        }
    }

    @Override
    public Optional<DocumentRecord> findDocument(SpaceId space, UUID id) {
        return Optional.ofNullable(documents.get(id)).filter(d -> d.space().equals(space));
    }

    @Override
    public Optional<DocumentRecord> findActiveByHash(SpaceId space, String contentHash) {
        return documents.values().stream()
                .filter(d -> d.space().equals(space) && d.status() == DocumentStatus.ACTIVE
                        && d.contentHash().equals(contentHash))
                .findFirst();
    }

    @Override
    public List<DocumentRecord> listDocuments(SpaceId space) {
        return documents.values().stream()
                .filter(d -> d.space().equals(space))
                .sorted(Comparator.comparing(DocumentRecord::sourceName).thenComparing(DocumentRecord::version))
                .toList();
    }

    @Override
    public void updateStatus(UUID documentId, DocumentStatus status, String error) {
        synchronized (writeLock) {
            documents.computeIfPresent(documentId, (id, d) -> d.withStatus(status, error));
        }
    }

    @Override
    public void saveChunks(UUID documentId, List<ParentChunk> parentChunks, List<Chunk> childChunks) {
        synchronized (writeLock) {
            parentChunks.forEach(p -> parents.put(p.id(), p));
            for (Chunk c : childChunks) {
                chunks.put(c.id(), c);
                chunkTerms.put(c.id(), TextAnalyzer.terms(c.text()));
            }
        }
    }

    @Override
    public void activate(UUID documentId, String embeddingModel, int chunkCount) {
        synchronized (writeLock) {
            DocumentRecord doc = documents.get(documentId);
            if (doc == null) {
                throw new IllegalStateException("Unknown document " + documentId);
            }
            for (DocumentRecord other : List.copyOf(documents.values())) {
                if (!other.id().equals(documentId) && other.space().equals(doc.space())
                        && other.sourceName().equals(doc.sourceName()) && other.status() == DocumentStatus.ACTIVE) {
                    removeChunksOf(other.id());
                    documents.put(other.id(), other.withStatus(DocumentStatus.SUPERSEDED, null));
                }
            }
            documents.put(documentId, doc.activated(embeddingModel, chunkCount));
            corpusVersions.merge(doc.space(), 1L, Long::sum);
        }
    }

    @Override
    public void discardChunks(UUID documentId) {
        synchronized (writeLock) {
            removeChunksOf(documentId);
        }
    }

    @Override
    public boolean deleteDocument(SpaceId space, UUID documentId) {
        synchronized (writeLock) {
            DocumentRecord doc = documents.get(documentId);
            if (doc == null || !doc.space().equals(space)) {
                return false;
            }
            for (DocumentRecord d : List.copyOf(documents.values())) {
                if (d.space().equals(space) && d.sourceName().equals(doc.sourceName())) {
                    removeChunksOf(d.id());
                    documents.remove(d.id());
                }
            }
            corpusVersions.merge(space, 1L, Long::sum);
            return true;
        }
    }

    private void removeChunksOf(UUID documentId) {
        chunks.values().removeIf(c -> {
            boolean match = c.documentId().equals(documentId);
            if (match) {
                chunkTerms.remove(c.id());
            }
            return match;
        });
        parents.values().removeIf(p -> p.documentId().equals(documentId));
    }

    private List<Chunk> searchable(Set<SpaceId> spaces) {
        List<Chunk> out = new ArrayList<>();
        for (Chunk c : chunks.values()) {
            DocumentRecord d = documents.get(c.documentId());
            if (d != null && d.status() == DocumentStatus.ACTIVE && spaces.contains(c.space())) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public List<RetrievedChunk> vectorSearch(Set<SpaceId> spaces, float[] queryEmbedding, int k) {
        return searchable(spaces).stream()
                .map(c -> toHit(c, TextAnalyzer.cosine(c.embedding(), queryEmbedding), 0))
                .sorted(Comparator.comparingDouble(RetrievedChunk::vectorScore).reversed())
                .limit(k)
                .toList();
    }

    @Override
    public List<RetrievedChunk> keywordSearch(Set<SpaceId> spaces, String query, float[] queryEmbedding, int k) {
        List<Chunk> candidates = searchable(spaces);
        List<List<String>> docs = candidates.stream().map(c -> chunkTerms.getOrDefault(c.id(), List.of())).toList();
        double[] scores = Bm25.score(query, docs);
        List<RetrievedChunk> hits = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (scores[i] > 0) {
                Chunk c = candidates.get(i);
                hits.add(toHit(c, TextAnalyzer.cosine(c.embedding(), queryEmbedding), scores[i]));
            }
        }
        hits.sort(Comparator.comparingDouble(RetrievedChunk::keywordScore).reversed());
        return hits.size() > k ? List.copyOf(hits.subList(0, k)) : hits;
    }

    private static RetrievedChunk toHit(Chunk c, double cosine, double keyword) {
        return new RetrievedChunk(c.id(), c.documentId(), c.parentId(), c.space(), c.text(), c.sourceName(),
                c.page(), c.section(), cosine, keyword, 0, cosine);
    }

    @Override
    public Map<UUID, String> parentTexts(Collection<UUID> parentIds) {
        Map<UUID, String> out = new HashMap<>();
        for (UUID id : parentIds) {
            ParentChunk p = parents.get(id);
            if (p != null) {
                out.put(id, p.text());
            }
        }
        return out;
    }

    @Override
    public Map<SpaceId, Long> corpusVersions(Set<SpaceId> spaces) {
        Map<SpaceId, Long> out = new HashMap<>();
        spaces.forEach(s -> out.put(s, corpusVersions.getOrDefault(s, 0L)));
        return out;
    }

    @Override
    public int failInterruptedIngestions() {
        int n = 0;
        synchronized (writeLock) {
            for (DocumentRecord d : List.copyOf(documents.values())) {
                if (d.status() == DocumentStatus.QUEUED || d.status() == DocumentStatus.INDEXING) {
                    removeChunksOf(d.id());
                    documents.put(d.id(), d.withStatus(DocumentStatus.FAILED, "Interrupted by restart"));
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    public long countChunksWithOtherModel(String currentModel) {
        return documents.values().stream()
                .filter(d -> d.status() == DocumentStatus.ACTIVE && d.embeddingModel() != null
                        && !d.embeddingModel().equals(currentModel))
                .mapToLong(DocumentRecord::chunkCount).sum();
    }
}
