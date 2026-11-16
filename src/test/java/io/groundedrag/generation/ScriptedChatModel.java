package io.groundedrag.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** A deterministic ChatModel for tests: records prompts and answers with a scripted function. */
public class ScriptedChatModel implements ChatModel {

    public final List<Prompt> prompts = new ArrayList<>();
    private final Function<Prompt, String> script;

    public ScriptedChatModel(Function<Prompt, String> script) {
        this.script = script;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(script.apply(prompt)))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        String text = script.apply(prompt);
        return Flux.fromArray(text.split("(?<= )"))
                .map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
    }

    public static String user(Prompt p) {
        return p.getUserMessage().getText();
    }

    public static String system(Prompt p) {
        return p.getSystemMessage().getText();
    }
}
