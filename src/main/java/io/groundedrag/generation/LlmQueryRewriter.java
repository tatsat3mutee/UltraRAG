package io.groundedrag.generation;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;

public class LlmQueryRewriter implements QueryRewriter {

    private final ChatClient chat;

    public LlmQueryRewriter(ChatClient chat) {
        this.chat = chat;
    }

    @Override
    public String standalone(List<Turn> history, String question) {
        if (history.isEmpty()) {
            return question; // no LLM round-trip for first questions
        }
        StringBuilder convo = new StringBuilder();
        for (Turn t : history) {
            convo.append("User: ").append(t.question()).append('\n')
                    .append("Assistant: ").append(abbreviate(t.answer())).append('\n');
        }
        String rewritten = chat.prompt()
                .system(Prompts.CONDENSE_SYSTEM)
                .user("Conversation:\n" + convo + "\nLatest message: " + question)
                .call().content();
        return clean(rewritten, question);
    }

    @Override
    public String reformulate(String question) {
        return clean(chat.prompt().system(Prompts.REFORMULATE_SYSTEM).user(question).call().content(), question);
    }

    private static String clean(String out, String fallback) {
        if (out == null || out.isBlank()) {
            return fallback;
        }
        String line = out.strip().lines().findFirst().orElse(fallback).strip();
        return line.length() > 500 ? fallback : line;
    }

    private static String abbreviate(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
