package io.groundedrag.retrieval;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Wraps the Spring AI {@link EmbeddingModel} with what the rest of the app needs: a stable model
 * identity (stored on every document so a model change is detectable), batching, and a
 * dimension check that fails fast instead of corrupting the index.
 */
public class Embedder {

    private final EmbeddingModel model;
    private final String modelName;
    private final int dimensions;
    private final int batchSize;

    public Embedder(EmbeddingModel model, String modelName, int dimensions, int batchSize) {
        this.model = model;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.batchSize = Math.max(1, batchSize);
    }

    public String modelName() {
        return modelName;
    }

    public int dimensions() {
        return dimensions;
    }

    public float[] embed(String text) {
        return check(model.embed(text));
    }

    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + batchSize));
            for (float[] v : model.embed(batch)) {
                out.add(check(v));
            }
        }
        return out;
    }

    private float[] check(float[] v) {
        if (v.length != dimensions) {
            throw new IllegalStateException("Embedding model '" + modelName + "' returned " + v.length
                    + " dimensions but rag.embedding.dimensions is " + dimensions
                    + ". The pgvector column is fixed-size; re-create the index for a new model.");
        }
        return v;
    }
}
