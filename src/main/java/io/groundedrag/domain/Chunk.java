package io.groundedrag.domain;

import java.util.UUID;

/** A small, embedded passage used for matching. Points at the parent passage it was cut from. */
public record Chunk(
        UUID id,
        UUID documentId,
        UUID parentId,
        SpaceId space,
        int ordinal,
        String text,
        String sourceName,
        Integer page,
        String section,
        float[] embedding) {
}
