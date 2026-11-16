package io.groundedrag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.groundedrag.config.RagProperties;
import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.domain.TextAnalyzer;
import io.groundedrag.store.KnowledgeStore;

/**
 * Dense + keyword retrieval fused with Reciprocal Rank Fusion, near-duplicate removal, then
 * reranking and a confidence verdict.
 *
 * <p>Why RRF: cosine and BM25/ts_rank live on unrelated scales, so a weighted sum needs per-corpus
 * tuning. RRF only uses ranks ({@code 1 / (k + rank)}), needs no normalisation and is robust.
 * Why both: embeddings miss exact identifiers (error codes, SKUs, plan names); keyword search
 * misses paraphrases. The evaluation harness measures each mode separately.
 */
public class HybridRetriever {

    private final KnowledgeStore store;
    private final Reranker reranker;
    private final RagProperties.Retrieval cfg;
    private final RagProperties.Confidence thresholds;

    public HybridRetriever(KnowledgeStore store, Reranker reranker, RagProperties.Retrieval cfg,
            RagProperties.Confidence thresholds) {
        this.store = store;
        this.reranker = reranker;
        this.cfg = cfg;
        this.thresholds = thresholds;
    }

    public RetrievalResult retrieve(String query, float[] queryEmbedding, Set<SpaceId> spaces) {
        return retrieve(query, queryEmbedding, spaces, RetrievalMode.parse(cfg.mode()), cfg.topK());
    }

    public RetrievalResult retrieve(String query, float[] queryEmbedding, Set<SpaceId> spaces,
            RetrievalMode mode, int topK) {
        List<RetrievedChunk> vector = mode == RetrievalMode.KEYWORD ? List.of()
                : store.vectorSearch(spaces, queryEmbedding, cfg.candidateK());
        List<RetrievedChunk> keyword = mode == RetrievalMode.VECTOR ? List.of()
                : store.keywordSearch(spaces, query, queryEmbedding, cfg.candidateK());

        List<RetrievedChunk> fused = reciprocalRankFusion(List.of(vector, keyword), cfg.rrfK());
        List<RetrievedChunk> distinct = dropNearDuplicates(fused, cfg.dedupJaccard());
        List<RetrievedChunk> ranked = reranker.rerank(query, distinct, topK);

        double score = reranker.confidence(query, ranked);
        return new RetrievalResult(query, ranked, score, classify(score));
    }

    public ConfidenceLevel classify(double score) {
        if (score >= thresholds.high()) {
            return ConfidenceLevel.HIGH;
        }
        if (score >= thresholds.medium()) {
            return ConfidenceLevel.MEDIUM;
        }
        if (score >= thresholds.low()) {
            return ConfidenceLevel.LOW;
        }
        return ConfidenceLevel.NONE;
    }

    static List<RetrievedChunk> reciprocalRankFusion(List<List<RetrievedChunk>> rankings, int k) {
        Map<UUID, RetrievedChunk> byId = new LinkedHashMap<>();
        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (List<RetrievedChunk> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                RetrievedChunk c = ranking.get(rank);
                scores.merge(c.id(), 1.0 / (k + rank + 1), Double::sum);
                // Keep the keyword score from whichever list reported it.
                byId.merge(c.id(), c, (a, b) -> a.withKeywordScore(Math.max(a.keywordScore(), b.keywordScore())));
            }
        }
        List<RetrievedChunk> out = new ArrayList<>();
        byId.forEach((id, c) -> out.add(c.withFusedScore(scores.get(id))));
        out.sort(Comparator.comparingDouble(RetrievedChunk::fusedScore).reversed());
        return out;
    }

    static List<RetrievedChunk> dropNearDuplicates(List<RetrievedChunk> ranked, double threshold) {
        List<RetrievedChunk> kept = new ArrayList<>();
        List<Set<String>> keptTerms = new ArrayList<>();
        for (RetrievedChunk c : ranked) {
            Set<String> terms = new HashSet<>(TextAnalyzer.terms(c.text()));
            boolean dup = keptTerms.stream().anyMatch(t -> TextAnalyzer.jaccard(t, terms) >= threshold);
            if (!dup) {
                kept.add(c);
                keptTerms.add(terms);
            }
        }
        return kept;
    }
}
