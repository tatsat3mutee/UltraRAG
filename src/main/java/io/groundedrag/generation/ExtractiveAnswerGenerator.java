package io.groundedrag.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.groundedrag.domain.TextAnalyzer;
import io.groundedrag.retrieval.ContextSource;
import reactor.core.publisher.Flux;

/**
 * No-LLM answerer: returns the sentences from the evidence that best overlap the question, each
 * with its citation. Lets the full API (including verification) run offline and in CI, and is a
 * useful baseline: an LLM answer should beat it on the evaluation set or it isn't earning its cost.
 */
public class ExtractiveAnswerGenerator implements AnswerGenerator {

    private static final int MAX_SENTENCES = 3;

    record Candidate(String sentence, int source, double score, int order) {
    }

    @Override
    public String generate(String question, List<Turn> history, List<ContextSource> sources) {
        Set<String> q = new HashSet<>(TextAnalyzer.terms(question));
        List<Candidate> candidates = new ArrayList<>();
        int order = 0;
        for (ContextSource s : sources) {
            for (String sentence : s.text().split("(?<=[.!?])\\s+")) {
                List<String> terms = TextAnalyzer.terms(sentence);
                if (terms.isEmpty()) {
                    continue;
                }
                // Identifiers and figures ("NW-4291", "2026") are far more specific than words.
                double hits = terms.stream().filter(q::contains).distinct()
                        .mapToDouble(t -> t.chars().anyMatch(Character::isDigit) ? 3 : 1).sum();
                // Rank-weighted overlap: earlier sources are more relevant.
                double score = hits / Math.sqrt(terms.size()) / (1 + 0.15 * (s.number() - 1));
                if (hits > 0) {
                    candidates.add(new Candidate(sentence.strip(), s.number(), score, order++));
                }
            }
        }
        if (candidates.isEmpty()) {
            return "I don't know based on the provided documents.";
        }
        double best = candidates.stream().mapToDouble(Candidate::score).max().orElse(0);
        return candidates.stream()
                .filter(c -> c.score() >= best * 0.5)
                .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .limit(MAX_SENTENCES)
                .sorted(Comparator.comparingInt(Candidate::order))
                .map(c -> withCitation(c.sentence(), c.source()))
                .reduce((a, b) -> a + " " + b)
                .orElseThrow();
    }

    private static String withCitation(String sentence, int source) {
        String s = sentence.endsWith(".") || sentence.endsWith("!") || sentence.endsWith("?")
                ? sentence.substring(0, sentence.length() - 1) : sentence;
        return s + " [" + source + "].";
    }

    @Override
    public Flux<String> stream(String question, List<Turn> history, List<ContextSource> sources) {
        String answer = generate(question, history, sources);
        return Flux.fromArray(answer.split("(?<= )"));
    }

    @Override
    public String name() {
        return "extractive";
    }
}
