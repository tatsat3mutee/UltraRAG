package io.groundedrag.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.groundedrag.domain.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Cross-encoder reranking through HuggingFace text-embeddings-inference ({@code POST /rerank}),
 * e.g. {@code BAAI/bge-reranker-base}. A cross-encoder reads query and passage together, so it
 * catches paraphrases that cosine similarity and BM25 miss, and its sigmoid score is calibrated
 * enough to threshold.
 *
 * <p>Degrades to the input order if the service is down: reranking improves answers, it must not
 * take the whole query path down with it.
 */
public class TeiReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(TeiReranker.class);

    record RerankHit(int index, double score) {
    }

    private final RestClient http;
    private final NoopReranker fallback = new NoopReranker();

    public TeiReranker(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeout);
        rf.setReadTimeout(timeout);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        try {
            List<RerankHit> hits = http.post().uri("/rerank")
                    .body(Map.of("query", query, "texts", candidates.stream().map(RetrievedChunk::text).toList(),
                            "raw_scores", false, "truncate", true))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<RerankHit>>() {
                    });
            List<RetrievedChunk> out = new ArrayList<>();
            for (RerankHit h : hits) {
                out.add(candidates.get(h.index()).withRerankScore(h.score()));
            }
            out.sort(Comparator.comparingDouble(RetrievedChunk::rerankScore).reversed());
            return out.stream().limit(topN).toList();
        } catch (RuntimeException e) {
            log.warn("Reranker unavailable, falling back to fused order: {}", e.toString());
            return fallback.rerank(query, candidates, topN);
        }
    }

    @Override
    public String name() {
        return "tei";
    }
}
