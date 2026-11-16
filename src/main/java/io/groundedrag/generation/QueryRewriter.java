package io.groundedrag.generation;

import java.util.List;

public interface QueryRewriter {

    /**
     * Rewrites a follow-up ("and for annual plans?") into a question that can be searched on its own.
     * Retrieval, caching and tracing all use the standalone form; without it, retrieval only ever
     * sees the last message and follow-ups fail.
     */
    String standalone(List<Turn> history, String question);

    /** An alternative phrasing used for one corrective retry when the first retrieval is weak. */
    String reformulate(String question);
}
