package io.groundedrag.retrieval;

import java.util.Locale;

public enum RetrievalMode {
    HYBRID,
    VECTOR,
    KEYWORD;

    public static RetrievalMode parse(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
