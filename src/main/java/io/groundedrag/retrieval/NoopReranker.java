package io.groundedrag.retrieval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.TextAnalyzer;

/**
 * Keeps RRF order. Without a cross-encoder there is no calibrated relevance score, and raw cosine
 * is a poor answerability signal (its scale differs per model and it barely separates on-topic
 * from off-topic questions; see docs/EVALUATION.md). Confidence is therefore <em>evidence
 * coverage</em>: the share of the question's content terms present in the top evidence, with
 * identifier/number terms weighted double. It is model-independent and explainable, and it
 * fails on paraphrases, which is the measured reason to enable the reranker.
 */
public class NoopReranker implements Reranker {

    static final int EVIDENCE_CHUNKS = 3;

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        return candidates.stream()
                .map(c -> c.withRerankScore(Math.max(0, c.vectorScore())))
                .limit(topN)
                .toList();
    }

    @Override
    public double confidence(String query, List<RetrievedChunk> ranked) {
        return evidenceCoverage(query, ranked.stream().limit(EVIDENCE_CHUNKS).map(RetrievedChunk::text).toList());
    }

    static double evidenceCoverage(String query, List<String> evidence) {
        Set<String> terms = new HashSet<>(TextAnalyzer.terms(query));
        if (terms.isEmpty() || evidence.isEmpty()) {
            return 0;
        }
        Set<String> available = new HashSet<>();
        evidence.forEach(e -> available.addAll(TextAnalyzer.terms(e)));
        double total = 0;
        double found = 0;
        for (String t : terms) {
            double w = t.chars().anyMatch(Character::isDigit) ? 2 : 1;
            total += w;
            if (available.contains(t)) {
                found += w;
            }
        }
        return found / total;
    }

    @Override
    public String name() {
        return "none";
    }
}
