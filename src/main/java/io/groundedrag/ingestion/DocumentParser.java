package io.groundedrag.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Turns bytes into sections with citation metadata.
 * PDF: one section per page (page numbers end up in citations). Markdown: one section per heading.
 * Everything else allowed (DOCX, PPTX, HTML, ...): Apache Tika via Spring AI.
 */
public class DocumentParser {

    private static final Pattern MD_HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    private final Set<String> allowedExtensions;

    public DocumentParser(Set<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public static String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public void validate(String filename) {
        String ext = extension(filename);
        if (!allowedExtensions.contains(ext)) {
            throw new UnsupportedDocumentException(
                    "Unsupported file type '." + ext + "'. Allowed: " + allowedExtensions);
        }
    }

    public List<ParsedSection> parse(String filename, byte[] bytes) {
        validate(filename);
        String ext = extension(filename);
        List<ParsedSection> sections = switch (ext) {
            case "pdf" -> parsePdf(filename, bytes);
            case "md", "markdown" -> parseMarkdown(new String(bytes, StandardCharsets.UTF_8));
            case "txt", "csv" -> List.of(new ParsedSection(new String(bytes, StandardCharsets.UTF_8), null, null));
            default -> parseWithTika(filename, bytes);
        };
        List<ParsedSection> nonEmpty = sections.stream().filter(s -> s.text() != null && !s.text().isBlank()).toList();
        if (nonEmpty.isEmpty()) {
            throw new UnsupportedDocumentException("No extractable text in " + filename
                    + " (scanned PDFs need OCR, which is not enabled)");
        }
        return nonEmpty;
    }

    private static Resource resource(String filename, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private List<ParsedSection> parsePdf(String filename, byte[] bytes) {
        List<ParsedSection> out = new ArrayList<>();
        for (Document page : new PagePdfDocumentReader(resource(filename, bytes)).get()) {
            Object p = page.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
            out.add(new ParsedSection(page.getText(), p instanceof Number n ? n.intValue() : null, null));
        }
        return out;
    }

    private List<ParsedSection> parseWithTika(String filename, byte[] bytes) {
        List<ParsedSection> out = new ArrayList<>();
        for (Document d : new TikaDocumentReader(resource(filename, bytes)).get()) {
            out.add(new ParsedSection(d.getText(), null, null));
        }
        return out;
    }

    static List<ParsedSection> parseMarkdown(String text) {
        List<ParsedSection> out = new ArrayList<>();
        Matcher m = MD_HEADING.matcher(text);
        int start = 0;
        String heading = null;
        while (m.find()) {
            addIfText(out, text.substring(start, m.start()), heading);
            heading = m.group(2).trim();
            start = m.end();
        }
        addIfText(out, text.substring(start), heading);
        return out;
    }

    private static void addIfText(List<ParsedSection> out, String body, String heading) {
        if (!body.isBlank()) {
            out.add(new ParsedSection(body.strip(), null, heading));
        }
    }
}
