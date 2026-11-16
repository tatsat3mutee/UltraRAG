package io.groundedrag.domain;

import java.util.UUID;

/**
 * A search hit plus every score we know about it.
 *
 * <p>Scores are kept separately on purpose. RRF scores (~0.01-0.03) are only useful for ordering;
 * confidence decisions use {@code vectorScore} (raw cosine) or {@code rerankScore} (calibrated),
 * never the fused rank score.
 */
public record RetrievedChunk(
        UUID id,
        UUID documentId,
        UUID parentId,
        SpaceId space,
        String text,
        String sourceName,
        Integer page,
        String section,
        double vectorScore,
        double keywordScore,
        double fusedScore,
        double rerankScore) {

    public RetrievedChunk withFusedScore(double fused) {
        return new RetrievedChunk(id, documentId, parentId, space, text, sourceName, page, section,
                vectorScore, keywordScore, fused, rerankScore);
    }

    public RetrievedChunk withKeywordScore(double keyword) {
        return new RetrievedChunk(id, documentId, parentId, space, text, sourceName, page, section,
                vectorScore, keyword, fusedScore, rerankScore);
    }

    public RetrievedChunk withRerankScore(double rerank) {
        return new RetrievedChunk(id, documentId, parentId, space, text, sourceName, page, section,
                vectorScore, keywordScore, fusedScore, rerank);
    }
}
