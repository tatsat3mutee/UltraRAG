package io.groundedrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.groundedrag.Fixtures;
import org.junit.jupiter.api.Test;

class ParentChildChunkerTest {

    private final ParentChildChunker chunker = new ParentChildChunker(Fixtures.TOKENS, 120, 40, 12);

    private static String sentences(String prefix, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(prefix).append(" sentence number ").append(i).append(" talks about refunds and billing. ");
        }
        return sb.toString();
    }

    @Test
    void childrenRespectTheTokenLimitAndParentsContainTheirChildren() {
        var pieces = chunker.chunk(List.of(new ParsedSection(sentences("Alpha", 40), 3, "Refunds")));
        assertThat(pieces).hasSizeGreaterThan(1);
        for (var p : pieces) {
            assertThat(Fixtures.TOKENS.estimate(p.parentText())).isLessThanOrEqualTo(120);
            assertThat(p.page()).isEqualTo(3);
            assertThat(p.section()).isEqualTo("Refunds");
            for (String child : p.children()) {
                assertThat(Fixtures.TOKENS.estimate(child)).isLessThanOrEqualTo(40);
                assertThat(p.parentText()).contains(child);
            }
        }
    }

    @Test
    void adjacentChildrenOverlapBySentence() {
        var piece = chunker.chunk(List.of(new ParsedSection(sentences("Beta", 8), null, null))).get(0);
        List<String> children = piece.children();
        assertThat(children).hasSizeGreaterThan(1);
        String lastSentenceOfFirst = children.get(0).substring(children.get(0).lastIndexOf("Beta"));
        assertThat(children.get(1)).startsWith(lastSentenceOfFirst);
    }

    @Test
    void parentsNeverSpanTwoSectionsSoCitationsKeepCorrectPages() {
        var pieces = chunker.chunk(List.of(new ParsedSection("Page one text.", 1, null),
                new ParsedSection("Page two text.", 2, null)));
        assertThat(pieces).extracting(ParentChildChunker.Piece::page).containsExactly(1, 2);
    }

    @Test
    void splitsASingleOverlongSentenceOnWords() {
        String longSentence = "word ".repeat(200);
        var pieces = chunker.chunk(List.of(new ParsedSection(longSentence, null, null)));
        pieces.forEach(p -> p.children().forEach(c -> assertThat(Fixtures.TOKENS.estimate(c)).isLessThanOrEqualTo(40)));
    }
}
