package io.groundedrag.chat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.groundedrag.generation.Verification;
import io.groundedrag.retrieval.ConfidenceLevel;

/**
 * The API contract for an answer. Structured on purpose: clients render citations from
 * {@code sources}, show {@code notice} as a warning, and can hide or flag answers whose
 * {@code verification.grounded} is false.
 */
public record ChatAnswer(
        UUID answerId,
        String conversationId,
        String answer,
        String notice,
        String standaloneQuestion,
        Confidence confidence,
        List<Source> sources,
        Verification verification,
        boolean cached,
        Map<String, Long> timingsMs) {

    public record Confidence(ConfidenceLevel level, double score) {
    }

    public record Source(int number, UUID chunkId, String space, String sourceName, Integer page, String section,
            double score, String excerpt, boolean cited) {
    }

    ChatAnswer forReplay(UUID newId, String newConversationId, Map<String, Long> timings) {
        return new ChatAnswer(newId, newConversationId, answer, notice, standaloneQuestion, confidence, sources,
                verification, true, timings);
    }
}
