package io.groundedrag.domain;

/**
 * Lifecycle of one document version.
 *
 * <pre>
 * QUEUED -> INDEXING -> ACTIVE -> SUPERSEDED (a newer version became ACTIVE)
 *                    \-> FAILED (previous ACTIVE version, if any, keeps serving)
 * </pre>
 */
public enum DocumentStatus {
    QUEUED,
    INDEXING,
    ACTIVE,
    SUPERSEDED,
    FAILED
}
