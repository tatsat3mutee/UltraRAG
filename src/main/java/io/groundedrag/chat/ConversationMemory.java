package io.groundedrag.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.groundedrag.generation.Turn;

/**
 * Bounded, per-principal conversation history (LRU over conversations, last N turns each).
 * Keyed by principal + conversation id so one API key can never read another's conversation.
 *
 * <p>In-process by design for a single instance; for horizontal scaling this interface is the
 * seam to move it to Redis (see docs/adr/0006-state-placement.md).
 */
public class ConversationMemory {

    private final int maxTurns;
    private final Map<String, List<Turn>> conversations;

    public ConversationMemory(int maxTurns, int maxConversations) {
        this.maxTurns = maxTurns;
        this.conversations = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Turn>> eldest) {
                return size() > maxConversations;
            }
        };
    }

    public synchronized List<Turn> history(String principal, String conversationId) {
        List<Turn> turns = conversations.get(key(principal, conversationId));
        return turns == null ? List.of() : List.copyOf(turns);
    }

    public synchronized void append(String principal, String conversationId, Turn turn) {
        List<Turn> turns = conversations.computeIfAbsent(key(principal, conversationId), k -> new ArrayList<>());
        turns.add(turn);
        while (turns.size() > maxTurns) {
            turns.remove(0);
        }
    }

    private static String key(String principal, String conversationId) {
        return principal + "\u0000" + conversationId;
    }
}
