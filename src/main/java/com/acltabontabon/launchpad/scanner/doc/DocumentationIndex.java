package com.acltabontabon.launchpad.scanner.doc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Flat catalogue of documentation discovered in a project. Populated by
 * {@link DocumentationDetector} after the file-tree walk.
 * <p>
 * The early version of Launchpad is opinionated about what counts as
 * documentation: Markdown ({@code .md}, {@code .markdown}) and AsciiDoc
 * ({@code .adoc}, {@code .asciidoc}) only. Site-generator config files
 * (mkdocs.yml, antora.yml) are no longer inspected - clients that need that
 * level of detail can read the raw files via the file-read MCP tool.
 * <p>
 * {@code pages} is ordered by file-walk order. Older {@code .launchpad/scan.json}
 * caches with the legacy mode fields ({@code format}, {@code siteName},
 * {@code docsDir}) deserialize cleanly because {@link JsonIgnoreProperties}
 * drops unknown keys; the {@code pages} list survives and everything else is
 * implicitly empty.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentationIndex(List<DocumentationPage> pages) {

    public DocumentationIndex {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public static DocumentationIndex empty() {
        return new DocumentationIndex(List.of());
    }

    public boolean isEmpty() {
        return pages.isEmpty();
    }
}
