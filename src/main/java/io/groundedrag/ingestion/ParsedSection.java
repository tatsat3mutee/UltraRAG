package io.groundedrag.ingestion;

/** A run of text that shares citation metadata (a PDF page or a Markdown section). */
public record ParsedSection(String text, Integer page, String section) {
}
