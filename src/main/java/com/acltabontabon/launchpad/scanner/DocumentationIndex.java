package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Catalogue of documentation discovered in a project. Populated by
 * {@link DocumentationDetector} after the file-tree walk.
 * <p>
 * Markdown and AsciiDoc are first-class peers - {@link Format#MKDOCS} drives
 * a typical {@code mkdocs.yml + docs/*.md} layout, {@link Format#ANTORA} drives
 * an {@code antora.yml + *.adoc} layout, and {@link Format#PLAIN} catches loose
 * doc files (`.md`, `.adoc`, `.rst`) anywhere under common doc directories or
 * the project root. {@link Format#NONE} means no docs were detected.
 * <p>
 * {@code pages} is ordered. For MkDocs we honour the {@code nav} declaration
 * when present; for everything else the order is the file-walk order.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentationIndex(
    Format format,
    String siteName,
    String docsDir,
    List<DocumentationPage> pages
) {

    public enum Format { MKDOCS, ANTORA, PLAIN, NONE }

    public static DocumentationIndex none() {
        return new DocumentationIndex(Format.NONE, null, null, List.of());
    }

    public boolean isEmpty() {
        return format == Format.NONE || pages == null || pages.isEmpty();
    }
}
