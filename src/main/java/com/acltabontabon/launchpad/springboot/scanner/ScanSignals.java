package com.acltabontabon.launchpad.springboot.scanner;

import com.acltabontabon.launchpad.scanner.doc.DocumentationSignals;
import com.acltabontabon.launchpad.springboot.detectors.SpringProfileDetector;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable accumulator for file-tree signals captured during the Spring Boot
 * project walk. The visitor flips flags in-place; post-walk each detector
 * reads the slice it cares about. Package-private to its scanner package -
 * cross-package consumers (the framework-agnostic documentation detector)
 * read through a small projection record instead of touching this class.
 */
final class ScanSignals {

    // --- Spring ---
    /** META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports present. */
    boolean hasAutoConfigImports;
    /** Legacy META-INF/spring.factories present. */
    boolean hasSpringFactories;
    /** Any .java source contains {@code @AutoConfiguration}. */
    boolean hasAutoConfigAnnotation;

    // --- Documentation ---
    /** {@code mkdocs.yml} present at the project root. */
    boolean hasMkdocsConfig;
    /** {@code antora.yml} present anywhere in the tree. */
    boolean hasAntoraConfig;
    /** A {@code docs/}, {@code documentation/}, {@code doc/}, or {@code site/} directory exists. */
    boolean hasDocsFolder;
    /** Relative paths of every doc file (Markdown, AsciiDoc, RST) observed during the walk. */
    final List<String> docFiles = new ArrayList<>();

    SpringProfileDetector.Signals springSignals() {
        return new SpringProfileDetector.Signals(
            hasAutoConfigImports, hasSpringFactories, hasAutoConfigAnnotation);
    }

    DocumentationSignals documentationSignals() {
        return new DocumentationSignals(
            hasMkdocsConfig, hasAntoraConfig, hasDocsFolder, List.copyOf(docFiles));
    }
}
