package com.acltabontabon.launchpad.scanner.doc;

import com.acltabontabon.launchpad.scanner.doc.DocumentationPage.PageFormat;
import java.util.List;

/**
 * Extracts a human-friendly title from a documentation page. Pure - no IO.
 * <p>
 * The caller passes the first chunk of the file's content; this class picks
 * the right heading rule for the page format and falls back to a humanised
 * file stem when no heading is found.
 */
final class DocTitleExtractor {

    /** Max characters of content to scan for a heading. */
    static final int SCAN_CHARS = 4_000;

    /**
     * Extract a title from the given content + filename. {@code content} may be
     * a prefix; the extractor only looks at the first {@link #SCAN_CHARS} chars.
     */
    static String extract(String fileName, PageFormat format, String content) {
        String head = content == null ? "" :
            content.length() > SCAN_CHARS ? content.substring(0, SCAN_CHARS) : content;
        String title = switch (format) {
            case MARKDOWN -> markdownH1(head);
            case ASCIIDOC -> asciidocTitle(head);
        };
        if (title != null && !title.isBlank()) return title;
        return humaniseStem(fileName);
    }

    /** First line beginning with a single {@code #} (one hash) plus a space. */
    private static String markdownH1(String head) {
        for (String line : splitLines(head)) {
            String t = line.trim();
            if (t.startsWith("# ") && !t.startsWith("## ")) {
                return stripTrailingHashes(t.substring(2)).trim();
            }
        }
        return null;
    }

    /**
     * First line beginning with {@code =} followed by a space. AsciiDoc uses
     * one leading {@code =} for the document title and {@code ==}+ for
     * section headings, so we explicitly reject the latter.
     */
    private static String asciidocTitle(String head) {
        for (String line : splitLines(head)) {
            String t = line.trim();
            if (t.startsWith("= ") && !t.startsWith("== ")) {
                return t.substring(2).trim();
            }
        }
        return null;
    }

    /** Strip optional trailing {@code #}s allowed in ATX-style Markdown headings. */
    private static String stripTrailingHashes(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '#' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * {@code getting-started.md} -> "Getting Started". Drops the extension,
     * splits on dashes/underscores/dots, capitalises each word.
     */
    static String humaniseStem(String fileName) {
        if (fileName == null || fileName.isBlank()) return "";
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String[] parts = stem.split("[-_.\\s]+");
        var sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.toString();
    }

    private static List<String> splitLines(String s) {
        return s.lines().toList();
    }

    private DocTitleExtractor() {}
}
