package io.groundedrag.retrieval;

/** How well the retrieved evidence matches the question. Drives answer/caveat/decline behaviour. */
public enum ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW,
    NONE;

    public boolean atLeast(ConfidenceLevel other) {
        return ordinal() <= other.ordinal();
    }
}
