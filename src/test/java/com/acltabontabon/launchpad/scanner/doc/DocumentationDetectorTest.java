package com.acltabontabon.launchpad.scanner.doc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.acltabontabon.launchpad.scanner.doc.DocumentationPage.PageFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentationDetectorTest {

    private final DocumentationDetector detector =
        new DocumentationDetector(PurposeClassifier.deterministicOnly());

    @Test
    void emptyProjectYieldsEmptyIndex(@TempDir Path root) {
        var index = detector.detect(root, signals(List.of()));
        assertThat(index.isEmpty()).isTrue();
        assertThat(index.pages()).isEmpty();
    }

    @Test
    void surfacesMarkdownAndAsciiDocSideBySide(@TempDir Path root) throws IOException {
        var docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("guide.md"), "# Guide\n");
        Files.writeString(docs.resolve("reference.adoc"), "= Reference\n");

        var index = detector.detect(root,
            signals(List.of("docs/guide.md", "docs/reference.adoc")));
        assertThat(index.pages())
            .extracting(DocumentationPage::path, DocumentationPage::title, DocumentationPage::format)
            .containsExactly(
                tuple("docs/guide.md", "Guide", PageFormat.MARKDOWN),
                tuple("docs/reference.adoc", "Reference", PageFormat.ASCIIDOC));
    }

    @Test
    void taglinesEachPageWithAPurpose(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("README.md"), "# Project\n");
        Files.writeString(root.resolve("CHANGELOG.md"), "# Changelog\n");
        Files.writeString(root.resolve("CONTRIBUTING.md"), "# Contributing\n");
        var docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("setup.md"), "# Setup\n");
        Files.writeString(docs.resolve("api.adoc"), "= API\n");
        Files.createDirectories(docs.resolve("architecture"));
        Files.writeString(docs.resolve("architecture/overview.md"), "# Arch\n");
        Files.writeString(docs.resolve("random-notes.md"), "# Notes\n");

        var index = detector.detect(root, signals(List.of(
            "README.md", "CHANGELOG.md", "CONTRIBUTING.md",
            "docs/setup.md", "docs/api.adoc",
            "docs/architecture/overview.md", "docs/random-notes.md")));

        assertThat(index.pages())
            .extracting(DocumentationPage::path, DocumentationPage::purpose)
            .containsExactly(
                tuple("README.md", Purpose.OVERVIEW),
                tuple("CHANGELOG.md", Purpose.CHANGELOG),
                tuple("CONTRIBUTING.md", Purpose.CONTRIBUTION),
                tuple("docs/setup.md", Purpose.SETUP),
                tuple("docs/api.adoc", Purpose.API),
                tuple("docs/architecture/overview.md", Purpose.ARCHITECTURE),
                tuple("docs/random-notes.md", Purpose.UNKNOWN));
    }

    @Test
    void ignoresUnsupportedExtensions(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("legacy.rst"), "Legacy\n======\n");
        var docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("guide.md"), "# Guide\n");

        // Scanner may still surface stray paths; the detector must drop them.
        var index = detector.detect(root, signals(List.of("legacy.rst", "docs/guide.md")));
        assertThat(index.pages())
            .extracting(DocumentationPage::path)
            .containsExactly("docs/guide.md");
    }

    @Test
    void fallsBackToHumanisedFilenameWhenHeadingAbsent(@TempDir Path root) throws IOException {
        var docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("getting-started.md"), "no heading here\n");

        var index = detector.detect(root, signals(List.of("docs/getting-started.md")));
        assertThat(index.pages().get(0).title()).isEqualTo("Getting Started");
    }

    private static DocumentationSignals signals(List<String> docFiles) {
        return new DocumentationSignals(docFiles);
    }
}
