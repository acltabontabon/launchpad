package com.acltabontabon.launchpad.scanner.doc;

import com.acltabontabon.launchpad.scanner.doc.DocumentationPage.PageFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Builds a {@link DocumentationIndex} from the scanner's collected doc-file
 * paths. The pipeline is intentionally flat: every Markdown or AsciiDoc file
 * the scanner observed becomes a {@link DocumentationPage} with a heading-
 * extracted title and a {@link Purpose} from {@link PurposeClassifier}.
 * <p>
 * No site-generator awareness. MkDocs and Antora config files are not parsed,
 * nor is nav ordering honoured - the early-version scope is "expose every
 * .md/.adoc the project actually ships and let MCP clients filter by purpose."
 */
public final class DocumentationDetector {

    private final PurposeClassifier purposeClassifier;

    public DocumentationDetector() {
        this(PurposeClassifier.deterministicOnly());
    }

    public DocumentationDetector(PurposeClassifier purposeClassifier) {
        this.purposeClassifier = purposeClassifier == null
            ? PurposeClassifier.deterministicOnly()
            : purposeClassifier;
    }

    public DocumentationIndex detect(Path projectRoot, DocumentationSignals signals) {
        if (signals == null || signals.docFiles().isEmpty()) {
            return DocumentationIndex.empty();
        }
        var pages = new ArrayList<DocumentationPage>();
        var seen = new LinkedHashSet<String>();
        for (String rel : signals.docFiles()) {
            if (seen.contains(rel)) continue;
            PageFormat fmt = formatOf(rel);
            if (fmt == null) continue;          // ignore stray extensions the scanner may still surface
            Path file = projectRoot.resolve(rel);
            String title = titleFromFile(file, fmt);
            Purpose purpose = purposeClassifier.classify(rel, () -> readPrefix(file));
            pages.add(new DocumentationPage(rel, title, fmt, purpose));
            seen.add(rel);
        }
        return new DocumentationIndex(pages);
    }

    static PageFormat formatOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return PageFormat.MARKDOWN;
        if (lower.endsWith(".adoc") || lower.endsWith(".asciidoc")) return PageFormat.ASCIIDOC;
        return null;
    }

    private static String titleFromFile(Path file, PageFormat fmt) {
        return DocTitleExtractor.extract(file.getFileName().toString(), fmt, readPrefix(file));
    }

    /** Read only the first chunk of the file - enough to find a heading or feed the AI classifier. */
    private static String readPrefix(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            int limit = Math.min(bytes.length, DocTitleExtractor.SCAN_CHARS);
            return new String(bytes, 0, limit, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
