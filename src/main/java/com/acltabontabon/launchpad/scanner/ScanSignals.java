package com.acltabontabon.launchpad.scanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable accumulator for file-tree signals captured during the project walk.
 * The visitor flips flags in-place; post-walk each framework's profile
 * detector reads the slice it cares about.
 * <p>
 * Centralising these as a single object avoids a pile of {@code boolean[1]}
 * holders in {@link ProjectScanner} and keeps detection signals discoverable
 * (every new signal lands on this class with a doc comment).
 */
final class ScanSignals {

    // --- Spring ---
    /** META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports present. */
    boolean hasAutoConfigImports;
    /** Legacy META-INF/spring.factories present. */
    boolean hasSpringFactories;
    /** Any .java source contains {@code @AutoConfiguration}. */
    boolean hasAutoConfigAnnotation;

    // --- Databricks ---
    /** Asset Bundle manifest at project root. */
    boolean hasDatabricksYml;
    /** Any *.tf file present anywhere in the tree. */
    boolean hasTerraformFiles;
    /** Any *.tf file declares the {@code databricks/databricks} provider. */
    boolean hasDatabricksProvider;
    /** Any .py source contains DLT markers ({@code import dlt} / {@code @dlt.table}). */
    boolean hasDltSource;
    /** Any .sql source contains DLT SQL markers ({@code CREATE STREAMING LIVE TABLE}). */
    boolean hasDltSqlSource;
    /** Any .py source file present. */
    boolean hasPythonSource;
    /** Any .sql source file present. */
    boolean hasSqlSource;
    /** Any source file begins with the Databricks notebook magic comment. */
    boolean hasNotebookMagic;

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
}
