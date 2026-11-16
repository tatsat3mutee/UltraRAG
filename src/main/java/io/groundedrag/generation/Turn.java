package io.groundedrag.generation;

/** One exchange in a conversation, as remembered for follow-up questions. */
public record Turn(String question, String answer) {
}
