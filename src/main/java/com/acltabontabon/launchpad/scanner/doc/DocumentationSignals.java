package com.acltabontabon.launchpad.scanner.doc;

import java.util.List;

/**
 * Framework-agnostic projection of the file-tree signals the
 * {@link DocumentationDetector} cares about. Produced by whichever scanner
 * walked the project (e.g. the Spring Boot scanner's internal
 * {@code ScanSignals}) and passed to the detector across the package
 * boundary, so the detector never needs to depend on a framework-specific
 * accumulator type.
 */
public record DocumentationSignals(
    boolean hasMkdocsConfig,
    boolean hasAntoraConfig,
    boolean hasDocsFolder,
    List<String> docFiles
) {

    public DocumentationSignals {
        if (docFiles == null) docFiles = List.of();
    }
}
