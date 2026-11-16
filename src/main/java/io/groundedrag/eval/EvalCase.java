package io.groundedrag.eval;

import java.util.List;

/** One golden question. Empty {@code expectedSources} means the system should decline. */
public record EvalCase(String id, String type, List<String> spaces, String question, List<String> expectedSources,
        List<String> mustContain) {

    public boolean answerable() {
        return !expectedSources.isEmpty();
    }
}
