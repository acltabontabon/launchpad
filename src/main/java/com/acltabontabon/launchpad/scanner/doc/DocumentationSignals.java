package com.acltabontabon.launchpad.scanner.doc;

import java.util.List;

/**
 * Framework-agnostic projection of the file-tree signals the
 * {@link DocumentationDetector} cares about. Produced by whichever scanner
 * walked the project (e.g. the Spring Boot scanner's internal
 * {@code ScanSignals}) and passed to the detector across the package
 * boundary, so the detector never needs to depend on a framework-specific
 * accumulator type.
 * <p>
 * Shrunk to a single field after the doc pipeline was scoped down to
 * Markdown + AsciiDoc: MkDocs/Antora config presence and the doc-folder flag
 * are no longer consumed.
 */
public record DocumentationSignals(List<String> docFiles) {

    public DocumentationSignals {
        if (docFiles == null) docFiles = List.of();
    }
}
