package io.groundedrag.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything needed to debug or evaluate one answer after the fact: which chunks were used,
 * with real confidence, verification and timing values. Feedback attaches to it, so a thumbs-down
 * can be traced to the exact evidence the model saw.
 */
public record AnswerTrace(
        UUID id,
        String principal,
        List<String> spaces,
        String question,
        String standaloneQuestion,
        List<UUID> chunkIds,
        List<String> sourceNames,
        String confidence,
        double confidenceScore,
        boolean grounded,
        List<String> unsupportedNumbers,
        boolean cached,
        String generator,
        String reranker,
        Map<String, Long> timingsMs,
        Instant createdAt,
        Integer rating,
        String feedback) {

    public AnswerTrace withFeedback(int newRating, String comment) {
        return new AnswerTrace(id, principal, spaces, question, standaloneQuestion, chunkIds, sourceNames,
                confidence, confidenceScore, grounded, unsupportedNumbers, cached, generator, reranker, timingsMs,
                createdAt, newRating, comment);
    }
}
