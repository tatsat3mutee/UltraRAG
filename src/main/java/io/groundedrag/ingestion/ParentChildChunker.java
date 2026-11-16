package io.groundedrag.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * Small-to-big chunking. Children (~180 tokens) are embedded because small passages give sharp
 * similarity scores; parents (~700 tokens) are what the LLM reads, because small passages lose the
 * surrounding context needed to answer.
 *
 * <p>Boundaries follow sentences, parents never cross a page or section (so a citation's page
 * number is always right), and adjacent children overlap by a few sentences.
 */
public class ParentChildChunker {

    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}\\d\"'(\\[])|\\n\\s*\\n");

    public record Piece(String parentText, List<String> children, Integer page, String section) {
    }

    private final TokenCountEstimator tokens;
    private final int parentTokens;
    private final int childTokens;
    private final int overlapTokens;

    public ParentChildChunker(TokenCountEstimator tokens, int parentTokens, int childTokens, int overlapTokens) {
        if (childTokens > parentTokens || overlapTokens >= childTokens) {
            throw new IllegalArgumentException("Need overlap < child <= parent tokens");
        }
        this.tokens = tokens;
        this.parentTokens = parentTokens;
        this.childTokens = childTokens;
        this.overlapTokens = overlapTokens;
    }

    public List<Piece> chunk(List<ParsedSection> sections) {
        List<Piece> out = new ArrayList<>();
        for (ParsedSection section : sections) {
            for (List<String> parent : pack(sentences(section.text()), parentTokens, 0)) {
                List<String> children = pack(parent, childTokens, overlapTokens).stream()
                        .map(s -> String.join(" ", s)).toList();
                out.add(new Piece(String.join(" ", parent), children, section.page(), section.section()));
            }
        }
        return out;
    }

    /** Sentences, with any single sentence longer than a child split on word boundaries. */
    List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : SENTENCE_BREAK.split(text.strip())) {
            String s = raw.replaceAll("\\s+", " ").strip();
            if (s.isEmpty()) {
                continue;
            }
            if (tokens.estimate(s) <= childTokens) {
                out.add(s);
                continue;
            }
            StringBuilder buf = new StringBuilder();
            for (String word : s.split(" ")) {
                if (buf.length() > 0 && tokens.estimate(buf + " " + word) > childTokens) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                buf.append(buf.length() == 0 ? "" : " ").append(word);
            }
            if (buf.length() > 0) {
                out.add(buf.toString());
            }
        }
        return out;
    }

    /** Greedy packing of sentences into groups of at most {@code limit} tokens, with sentence overlap. */
    private List<List<String>> pack(List<String> sentences, int limit, int overlap) {
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentTokens = 0;
        for (String s : sentences) {
            int t = tokens.estimate(s);
            if (!current.isEmpty() && currentTokens + t > limit) {
                groups.add(current);
                List<String> carry = new ArrayList<>();
                int carryTokens = 0;
                for (int i = current.size() - 1; i >= 0 && overlap > 0; i--) {
                    int ct = tokens.estimate(current.get(i));
                    if (carryTokens + ct > overlap || carryTokens + ct + t > limit) {
                        break;
                    }
                    carry.add(0, current.get(i));
                    carryTokens += ct;
                }
                current = carry;
                currentTokens = carryTokens;
            }
            current.add(s);
            currentTokens += t;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }
}
