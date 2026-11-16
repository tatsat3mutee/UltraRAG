package io.groundedrag.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.groundedrag.domain.Chunk;
import io.groundedrag.domain.DocumentRecord;
import io.groundedrag.domain.DocumentStatus;
import io.groundedrag.domain.ParentChunk;
import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;

/**
 * Port for documents, chunks and search. Two adapters: {@link InMemoryKnowledgeStore} (zero-infra
 * dev and fast tests) and {@link JdbcKnowledgeStore} (Postgres + pgvector + full-text search).
 * Both are exercised by the same contract test, so behaviour cannot drift between them.
 *
 * <p>Search methods only ever see chunks of ACTIVE document versions in the requested spaces.
 */
public interface KnowledgeStore {

    /** Creates a new QUEUED version for (space, sourceName). Version = previous max + 1. */
    DocumentRecord createDocument(SpaceId space, String sourceName, String contentHash, String contentType);

    Optional<DocumentRecord> findDocument(SpaceId space, UUID id);

    Optional<DocumentRecord> findActiveByHash(SpaceId space, String contentHash);

    List<DocumentRecord> listDocuments(SpaceId space);

    void updateStatus(UUID documentId, DocumentStatus status, String error);

    void saveChunks(UUID documentId, List<ParentChunk> parents, List<Chunk> chunks);

    /**
     * Atomically: mark this version ACTIVE, mark any previous ACTIVE version of the same source
     * SUPERSEDED and drop its chunks, and bump the space's corpus version.
     */
    void activate(UUID documentId, String embeddingModel, int chunkCount);

    /** Removes chunks of a version that failed mid-way. The previous ACTIVE version is untouched. */
    void discardChunks(UUID documentId);

    /** Deletes every version of the document's source and bumps the corpus version. */
    boolean deleteDocument(SpaceId space, UUID documentId);

    List<RetrievedChunk> vectorSearch(Set<SpaceId> spaces, float[] queryEmbedding, int k);

    /** Keyword (BM25 / ts_rank) search. Also reports cosine against {@code queryEmbedding}. */
    List<RetrievedChunk> keywordSearch(Set<SpaceId> spaces, String query, float[] queryEmbedding, int k);

    Map<UUID, String> parentTexts(Collection<UUID> parentIds);

    /** Monotonic per-space counter, bumped on every activation or deletion. Used to key caches. */
    Map<SpaceId, Long> corpusVersions(Set<SpaceId> spaces);

    /** Marks QUEUED/INDEXING versions left over from a crash as FAILED. Returns how many. */
    int failInterruptedIngestions();

    /** Counts active chunks embedded with a different model than {@code currentModel}. */
    long countChunksWithOtherModel(String currentModel);
}
