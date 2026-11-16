package io.groundedrag.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.groundedrag.domain.TextAnalyzer;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic, offline embedding model (feature hashing of word unigrams, word bigrams and
 * character trigrams, signed, log-scaled, L2-normalised).
 *
 * <p>It is not a semantic model. It exists so the whole pipeline, the API and the retrieval
 * evaluation gate run in CI and on a laptop with no API key and no model download. Swap in a real
 * model with the openai or ollama profile; the evaluation harness then measures the difference.
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    public static final String MODEL_NAME = "hashing-v1";

    private final int dimensions;

    public HashingEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        List<String> inputs = request.getInstructions();
        for (int i = 0; i < inputs.size(); i++) {
            out.add(new Embedding(embedText(inputs.get(i)), i));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
        return embedText(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    float[] embedText(String text) {
        Map<String, Double> features = new HashMap<>();
        List<String> terms = TextAnalyzer.terms(text);
        for (int i = 0; i < terms.size(); i++) {
            String t = terms.get(i);
            features.merge("w:" + t, 1.0, Double::sum);
            if (i + 1 < terms.size()) {
                features.merge("b:" + t + "_" + terms.get(i + 1), 0.5, Double::sum);
            }
            String padded = "^" + t.toLowerCase(Locale.ROOT) + "$";
            for (int j = 0; j + 3 <= padded.length(); j++) {
                features.merge("c:" + padded.substring(j, j + 3), 0.25, Double::sum);
            }
        }
        float[] v = new float[dimensions];
        for (Map.Entry<String, Double> e : features.entrySet()) {
            int h = murmurMix(e.getKey());
            int idx = Math.floorMod(h, dimensions);
            float sign = ((h >>> 31) == 0) ? 1f : -1f;
            v[idx] += sign * (float) (1 + Math.log(e.getValue()));
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm > 0) {
            float inv = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < v.length; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }

    private static int murmurMix(String s) {
        int h = 0x9747b28c;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b;
            h *= 0x5bd1e995;
            h ^= h >>> 15;
        }
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return h;
    }
}
