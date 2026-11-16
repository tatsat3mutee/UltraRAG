package io.groundedrag.domain;

import java.util.UUID;

/** A large, coherent passage. Never embedded; returned to the LLM when one of its children matches. */
public record ParentChunk(UUID id, UUID documentId, SpaceId space, int ordinal, String text) {
}
