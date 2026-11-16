package io.groundedrag.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single tokenizer shared by BM25, the hashing embedder, the Postgres tsquery builder and the
 * grounding verifier. One definition of "a term" keeps those components consistent.
 */
public final class TextAnalyzer {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_'-]*");

    public static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "do", "does", "for", "from",
            "has", "have", "how", "i", "if", "in", "into", "is", "it", "its", "me", "my", "no", "not", "of",
            "on", "or", "our", "so", "that", "the", "their", "them", "then", "there", "these", "they",
            "this", "to", "was", "we", "were", "what", "when", "where", "which", "who", "why", "will",
            "with", "you", "your", "should", "would", "could", "about", "tell", "please", "any", "all",
            // Question fillers: they carry no topic, so they must not count as unmatched evidence.
            "much", "many", "long", "often", "get", "got", "need", "want", "did", "also", "someone", "anyone",
            "like", "able");

    private TextAnalyzer() {
    }

    /** Lower-cased content terms with stopwords removed and a light plural strip. */
    public static List<String> terms(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String t = m.group().replaceAll("'s$", "").replace("'", "");
            if (t.length() < 2 || STOPWORDS.contains(t)) {
                continue;
            }
            out.add(stem(t));
        }
        return out;
    }

    /** Deliberately tiny stemmer: enough to match "refunds" with "refund" without a dependency. */
    static String stem(String t) {
        if (t.length() > 4 && t.endsWith("ies")) {
            return t.substring(0, t.length() - 3) + "y";
        }
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss") && !t.endsWith("us")) {
            return t.substring(0, t.length() - 1);
        }
        return t;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int inter = 0;
        for (String s : a) {
            if (b.contains(s)) {
                inter++;
            }
        }
        return (double) inter / (a.size() + b.size() - inter);
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
