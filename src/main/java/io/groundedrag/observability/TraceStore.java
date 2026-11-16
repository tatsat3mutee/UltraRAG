package io.groundedrag.observability;

import java.util.Optional;
import java.util.UUID;

public interface TraceStore {

    void save(AnswerTrace trace);

    Optional<AnswerTrace> find(UUID id);

    /** Only the principal that received the answer may rate it. */
    boolean recordFeedback(UUID id, String principal, int rating, String comment);
}
