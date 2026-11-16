package io.groundedrag.generation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.groundedrag.retrieval.ContextSource;

/**
 * Cheap, deterministic post-generation checks that run on every answer (no extra LLM call):
 * citations must point at real sources, and every number in the answer must appear in the
 * evidence. Numbers are where hallucinations hurt most (prices, limits, dates) and where a
 * string check is reliable. A sampled LLM-as-judge faithfulness score lives in the offline
 * evaluation, where its cost and latency don't hit users.
 */
public class GroundingVerifier {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})]");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w\\[])\\d[\\d,]*(?:\\.\\d+)?(?![\\w\\]])");
    private static final Pattern REFUSAL = Pattern.compile("(?i)i don'?t know|couldn'?t find|not (?:in|covered by) the (?:provided )?documents");

    public Verification verify(String answer, List<ContextSource> sources) {
        Set<Integer> valid = new TreeSet<>();
        Set<Integer> invalid = new TreeSet<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= sources.size()) {
                valid.add(n);
            } else {
                invalid.add(n);
            }
        }

        StringBuilder evidence = new StringBuilder();
        sources.forEach(s -> evidence.append(normaliseNumbers(s.text())).append('\n'));
        String evidenceText = evidence.toString();
        Set<String> unsupported = new LinkedHashSet<>();
        Matcher nums = NUMBER.matcher(CITATION.matcher(answer).replaceAll(" "));
        while (nums.find()) {
            String raw = nums.group();
            String n = normaliseNumbers(raw);
            if (n.length() < 2 && !raw.contains(".")) {
                continue; // single digits ("step 1", "2 options") are too noisy to police
            }
            if (!Pattern.compile("(?<![\\d.])" + Pattern.quote(n) + "(?![\\d])").matcher(evidenceText).find()) {
                unsupported.add(raw);
            }
        }

        boolean refusal = REFUSAL.matcher(answer).find();
        boolean grounded = invalid.isEmpty() && unsupported.isEmpty() && (refusal || !valid.isEmpty());
        return new Verification(new ArrayList<>(valid), new ArrayList<>(invalid), new ArrayList<>(unsupported),
                refusal, grounded);
    }

    /** "1,250.00" and "1250" should match; trailing ".0" zeros don't change the value. */
    static String normaliseNumbers(String s) {
        String out = s.replaceAll("(?<=\\d),(?=\\d{3})", "");
        out = out.replaceAll("(\\d+)\\.0+(?!\\d)", "$1");
        return out.toLowerCase(Locale.ROOT);
    }
}
