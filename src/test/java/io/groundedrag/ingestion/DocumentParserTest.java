package io.groundedrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser(Set.of("md", "pdf", "html", "txt"));

    @Test
    void markdownIsSplitByHeadingWithTheHeadingAsSection() {
        List<ParsedSection> s = parser.parse("a.md", """
                Intro line.
                # Refunds
                Thirty days.
                ## Fees
                None.
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(s).extracting(ParsedSection::section).containsExactly(null, "Refunds", "Fees");
        assertThat(s.get(1).text()).isEqualTo("Thirty days.");
    }

    @Test
    void pdfPagesKeepTheirPageNumbers() throws Exception {
        byte[] pdf = pdf("First page about leave.", "Second page about expenses.");
        List<ParsedSection> s = parser.parse("policy.pdf", pdf);
        assertThat(s).hasSize(2);
        assertThat(s.get(1).page()).isEqualTo(2);
        assertThat(s.get(1).text()).contains("expenses");
    }

    @Test
    void htmlGoesThroughTika() {
        List<ParsedSection> s = parser.parse("page.html",
                "<html><body><h1>Title</h1><p>Body text here.</p><script>evil()</script></body></html>"
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(s.get(0).text()).contains("Body text here").doesNotContain("evil");
    }

    @Test
    void rejectsUnknownTypesAndEmptyDocuments() {
        assertThatThrownBy(() -> parser.parse("x.exe", new byte[] {1})).isInstanceOf(UnsupportedDocumentException.class);
        assertThatThrownBy(() -> parser.parse("empty.txt", "  \n ".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UnsupportedDocumentException.class);
    }

    private static byte[] pdf(String... pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
