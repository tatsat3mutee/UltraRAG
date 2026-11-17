package io.groundedrag.web;

import java.util.Set;
import java.util.UUID;

import io.groundedrag.observability.AnswerTrace;
import io.groundedrag.observability.TraceStore;
import io.groundedrag.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class AnswerController {

    record Feedback(int rating, @Size(max = 2000) String comment) {
    }

    record Me(String name, Set<String> spaces, String authMethod) {
    }

    private final TraceStore traces;

    AnswerController(TraceStore traces) {
        this.traces = traces;
    }

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal Caller principal) {
        return new Me(principal.name(), principal.spaces(), principal.method());
    }

    /** The full trace behind an answer: chunk ids, scores, verification and stage timings. */
    @GetMapping("/answers/{id}")
    AnswerTrace trace(@AuthenticationPrincipal Caller principal, @PathVariable UUID id) {
        return traces.find(id)
                .filter(t -> t.principal().equals(principal.name()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/answers/{id}/feedback")
    ResponseEntity<Void> feedback(@AuthenticationPrincipal Caller principal, @PathVariable UUID id,
            @Valid @RequestBody Feedback feedback) {
        if (feedback.rating() != 1 && feedback.rating() != -1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating must be 1 or -1");
        }
        return traces.recordFeedback(id, principal.name(), feedback.rating(), feedback.comment())
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
