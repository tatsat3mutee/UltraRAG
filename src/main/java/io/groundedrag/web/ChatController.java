package io.groundedrag.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.groundedrag.chat.ChatAnswer;
import io.groundedrag.chat.RagService;
import io.groundedrag.security.ApiKeyPrincipal;
import io.groundedrag.security.SpaceAuthorizer;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    record ChatRequest(
            @NotBlank @Size(max = 2000) String question,
            @Size(max = 100) String conversationId,
            @Size(max = 20) List<String> spaces) {
    }

    private final RagService rag;
    // Streaming responses hold a thread while the model generates; virtual threads make that cheap.
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

    ChatController(RagService rag) {
        this.rag = rag;
    }

    @PostMapping
    ChatAnswer ask(@AuthenticationPrincipal ApiKeyPrincipal principal, @Valid @RequestBody ChatRequest req) {
        return rag.ask(toQuestion(principal, req));
    }

    /**
     * Server-sent events: {@code meta} (confidence + sources, before generation), {@code token}*,
     * then {@code done} (final answer with verification). Sources arrive first so the UI can render
     * evidence while the answer streams.
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal ApiKeyPrincipal principal, @Valid @RequestBody ChatRequest req) {
        RagService.Question q = toQuestion(principal, req); // authorise before going async
        SseEmitter emitter = new SseEmitter(120_000L);
        streams.execute(() -> {
            try {
                rag.stream(q, new RagService.StreamListener() {
                    @Override
                    public void onMeta(ChatAnswer partial) {
                        send(emitter, "meta", partial);
                    }

                    @Override
                    public void onToken(String token) {
                        send(emitter, "token", Map.of("text", token));
                    }

                    @Override
                    public void onComplete(ChatAnswer complete) {
                        send(emitter, "done", complete);
                    }
                });
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("Streaming answer failed", e);
                send(emitter, "error", Map.of("message", "The answer could not be generated. Please retry."));
                emitter.complete();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new ClientGoneException(e);
        }
    }

    private static RagService.Question toQuestion(ApiKeyPrincipal principal, ChatRequest req) {
        return new RagService.Question(principal.name(), SpaceAuthorizer.resolve(principal, req.spaces()),
                req.question().strip(), req.conversationId());
    }

    @PreDestroy
    void shutdown() {
        streams.shutdown();
    }

    /** The browser disconnected mid-stream; stop generating. */
    static class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) {
            super(cause);
        }
    }
}
