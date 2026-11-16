package io.groundedrag.generation;

import java.util.List;

/**
 * Result of checking an answer against its evidence.
 *
 * @param citedSources      source numbers the answer cites that exist
 * @param invalidCitations  cited numbers with no matching source (a hallucinated citation)
 * @param unsupportedNumbers numbers in the answer that appear in no source (likely invented figures)
 * @param refusal           the model said it doesn't know
 * @param grounded          no invalid citations, no unsupported numbers, and cites something (or refused)
 */
public record Verification(List<Integer> citedSources, List<Integer> invalidCitations,
        List<String> unsupportedNumbers, boolean refusal, boolean grounded) {
}
