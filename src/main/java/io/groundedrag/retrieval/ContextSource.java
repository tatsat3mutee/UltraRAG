package io.groundedrag.retrieval;

import io.groundedrag.domain.RetrievedChunk;

/** A numbered piece of evidence handed to the model. {@code number} is what the answer cites as [n]. */
public record ContextSource(int number, RetrievedChunk chunk, String text, boolean expandedToParent) {
}
