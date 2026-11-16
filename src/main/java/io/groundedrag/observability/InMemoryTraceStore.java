package io.groundedrag.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Bounded in-process trace store for the memory profile. */
public class InMemoryTraceStore implements TraceStore {

    private static final int MAX = 5000;

    private final Map<UUID, AnswerTrace> traces = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, AnswerTrace> eldest) {
            return size() > MAX;
        }
    };

    @Override
    public synchronized void save(AnswerTrace trace) {
        traces.put(trace.id(), trace);
    }

    @Override
    public synchronized Optional<AnswerTrace> find(UUID id) {
        return Optional.ofNullable(traces.get(id));
    }

    @Override
    public synchronized boolean recordFeedback(UUID id, String principal, int rating, String comment) {
        AnswerTrace t = traces.get(id);
        if (t == null || !t.principal().equals(principal)) {
            return false;
        }
        traces.put(id, t.withFeedback(rating, comment));
        return true;
    }
}
