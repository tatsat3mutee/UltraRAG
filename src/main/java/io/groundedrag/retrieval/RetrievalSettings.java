package io.groundedrag.retrieval;

/**
 * Retrieval tunables, owned by this package so it does not depend on the Spring configuration
 * tree (config maps rag.retrieval.* and rag.confidence.* onto these).
 */
public record RetrievalSettings(RetrievalMode mode, int candidateK, int topK, int rrfK, double dedupJaccard) {

    /** Confidence cut-offs, highest first. */
    public record Thresholds(double high, double medium, double low) {
    }
}
