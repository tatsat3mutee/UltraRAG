package io.groundedrag.domain;

import java.time.Instant;
import java.util.UUID;

/** One version of an uploaded document. The row doubles as the ingestion job record. */
public record DocumentRecord(
        UUID id,
        SpaceId space,
        String sourceName,
        String contentHash,
        int version,
        DocumentStatus status,
        String contentType,
        int chunkCount,
        String embeddingModel,
        String error,
        Instant createdAt,
        Instant updatedAt) {

    public DocumentRecord withStatus(DocumentStatus newStatus, String newError) {
        return new DocumentRecord(id, space, sourceName, contentHash, version, newStatus, contentType,
                chunkCount, embeddingModel, newError, createdAt, Instant.now());
    }

    public DocumentRecord activated(String model, int chunks) {
        return new DocumentRecord(id, space, sourceName, contentHash, version, DocumentStatus.ACTIVE, contentType,
                chunks, model, null, createdAt, Instant.now());
    }
}
