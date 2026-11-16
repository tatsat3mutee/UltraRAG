package io.groundedrag.retrieval;

import java.util.List;

import io.groundedrag.domain.RetrievedChunk;

public record RetrievalResult(String query, List<RetrievedChunk> chunks, double confidenceScore,
        ConfidenceLevel confidence) {
}
