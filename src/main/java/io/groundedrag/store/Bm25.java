package io.groundedrag.store;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.groundedrag.domain.TextAnalyzer;

/**
 * Okapi BM25 over an in-memory candidate set (k1 = 1.2, b = 0.75).
 * Statistics are computed per call, which is fine for the dev/test store sizes it serves.
 */
final class Bm25 {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private Bm25() {
    }

    static double[] score(String query, List<List<String>> docs) {
        Set<String> qTerms = new HashSet<>(TextAnalyzer.terms(query));
        double[] scores = new double[docs.size()];
        if (qTerms.isEmpty() || docs.isEmpty()) {
            return scores;
        }
        Map<String, Integer> df = new HashMap<>();
        double totalLen = 0;
        for (List<String> d : docs) {
            totalLen += d.size();
            for (String t : new HashSet<>(d)) {
                if (qTerms.contains(t)) {
                    df.merge(t, 1, Integer::sum);
                }
            }
        }
        double avgLen = totalLen / docs.size();
        int n = docs.size();
        for (int i = 0; i < n; i++) {
            List<String> d = docs.get(i);
            Map<String, Integer> tf = new HashMap<>();
            for (String t : d) {
                if (qTerms.contains(t)) {
                    tf.merge(t, 1, Integer::sum);
                }
            }
            double s = 0;
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                int dfi = df.getOrDefault(e.getKey(), 0);
                double idf = Math.log(1 + (n - dfi + 0.5) / (dfi + 0.5));
                double f = e.getValue();
                s += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * d.size() / avgLen));
            }
            scores[i] = s;
        }
        return scores;
    }
}
