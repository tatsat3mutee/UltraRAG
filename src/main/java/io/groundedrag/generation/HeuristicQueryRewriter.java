package io.groundedrag.generation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.groundedrag.domain.TextAnalyzer;

/**
 * Offline rewriter used when no LLM is configured. Follow-ups that look dependent (short, or
 * containing a pronoun/ellipsis) inherit the content terms of the previous question.
 */
public class HeuristicQueryRewriter implements QueryRewriter {

    private static final Pattern DEPENDENT = Pattern.compile(
            "(?i)\\b(it|its|they|them|that|this|those|these|he|she|what about|how about|and for|same)\\b");

    @Override
    public String standalone(List<Turn> history, String question) {
        if (history.isEmpty()) {
            return question;
        }
        boolean dependent = DEPENDENT.matcher(question).find() || TextAnalyzer.terms(question).size() <= 3;
        if (!dependent) {
            return question;
        }
        Set<String> carried = new LinkedHashSet<>(TextAnalyzer.terms(history.get(history.size() - 1).question()));
        carried.removeAll(TextAnalyzer.terms(question));
        return carried.isEmpty() ? question : question + " (context: " + String.join(" ", carried) + ")";
    }

    @Override
    public String reformulate(String question) {
        return String.join(" ", TextAnalyzer.terms(question));
    }
}
