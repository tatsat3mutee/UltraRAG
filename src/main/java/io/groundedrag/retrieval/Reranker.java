package io.groundedrag.retrieval;

import java.util.List;

import io.groundedrag.domain.RetrievedChunk;

/**
 * Re-orders fused candidates and sets {@link RetrievedChunk#rerankScore()} to a score in [0, 1]
 * that the confidence policy can threshold.
 */
public interface Reranker {

    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);

    String name();

    /**
     * How well the ranked evidence answers the query, in [0, 1]. The default is the top
     * calibrated rerank score, which is right for cross-encoders.
     */
    default double confidence(String query, List<RetrievedChunk> ranked) {
        return ranked.isEmpty() ? 0 : ranked.get(0).rerankScore();
    }
}
