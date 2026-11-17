package io.groundedrag.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public abstract class TraceStoreContract {

    protected abstract TraceStore store();

    private AnswerTrace trace(String principal) {
        return new AnswerTrace(UUID.randomUUID(), principal, List.of("product"), "q?", "standalone q?",
                List.of(UUID.randomUUID(), UUID.randomUUID()), List.of("a.md"), "HIGH", 0.91, true, List.of(),
                false, "llm", "tei", Map.of("retrieve", 12L, "generate", 640L),
                Instant.now().truncatedTo(ChronoUnit.MILLIS), null, null);
    }

    @Test
    void roundTripsEveryField() {
        AnswerTrace t = trace("alice");
        store().save(t);
        assertThat(store().find(t.id())).contains(t);
    }

    @Test
    void feedbackIsOwnerOnly() {
        AnswerTrace t = trace("alice");
        store().save(t);
        assertThat(store().recordFeedback(t.id(), "mallory", -1, "nope")).isFalse();
        assertThat(store().recordFeedback(t.id(), "alice", -1, "wrong figure")).isTrue();
        AnswerTrace rated = store().find(t.id()).orElseThrow();
        assertThat(rated.rating()).isEqualTo(-1);
        assertThat(rated.feedback()).isEqualTo("wrong figure");
        assertThat(store().recordFeedback(UUID.randomUUID(), "alice", 1, null)).isFalse();
    }
}
