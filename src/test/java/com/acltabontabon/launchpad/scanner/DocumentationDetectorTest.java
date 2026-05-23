package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.DocumentationIndex.Format;
import com.acltabontabon.launchpad.scanner.DocumentationPage.PageFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentationDetectorTest {

    private final DocumentationDetector detector = new DocumentationDetector();

    @Test
    void emptyProjectYieldsNone(@TempDir Path root) {
        var signals = new ScanSignals();
        var index = detector.detect(root, signals);
        assertThat(index.format()).isEqualTo(Format.NONE);
        assertThat(index.isEmpty()).isTrue();
    }

    @Test
    void mkdocsModeOrdersByNavThenAppendsUnlistedPages(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("mkdocs.yml"), """
            site_name: Sample
            nav:
              - Home: index.md
              - User Guide:
                  - Setup: guide/setup.md
            """);
        var docsDir = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docsDir.resolve("index.md"), "# Welcome\n");
        Files.createDirectories(docsDir.resolve("guide"));
        Files.writeString(docsDir.resolve("guide/setup.md"), "# Setting Up\n");
        Files.writeString(docsDir.resolve("orphan.md"), "# Not In Nav\n");

        var signals = new ScanSignals();
        signals.hasMkdocsConfig = true;
        signals.docFiles.add("docs/index.md");
        signals.docFiles.add("docs/guide/setup.md");
        signals.docFiles.add("docs/orphan.md");

        var index = detector.detect(root, signals);
        assertThat(index.format()).isEqualTo(Format.MKDOCS);
        assertThat(index.siteName()).isEqualTo("Sample");
        assertThat(index.docsDir()).isEqualTo("docs");
        assertThat(index.pages())
            .extracting(DocumentationPage::path)
            .containsExactly("docs/index.md", "docs/guide/setup.md", "docs/orphan.md");
        assertThat(index.pages().get(0).title()).isEqualTo("Home");
        assertThat(index.pages().get(1).title()).isEqualTo("User Guide / Setup");
        assertThat(index.pages().get(2).title()).isEqualTo("Not In Nav");
    }

    @Test
    void mkdocsModeFallsBackToFileWalkWhenNavMissing(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("mkdocs.yml"), "site_name: X\n");
        var docsDir = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docsDir.resolve("a.md"), "# A\n");
        Files.writeString(docsDir.resolve("b.md"), "# B\n");

        var signals = new ScanSignals();
        signals.hasMkdocsConfig = true;
        signals.docFiles.add("docs/a.md");
        signals.docFiles.add("docs/b.md");

        var index = detector.detect(root, signals);
        assertThat(index.format()).isEqualTo(Format.MKDOCS);
        assertThat(index.pages())
            .extracting(DocumentationPage::path)
            .containsExactly("docs/a.md", "docs/b.md");
    }

    @Test
    void antoraModeListsAsciiDocPagesAndIgnoresMarkdown(@TempDir Path root) throws IOException {
        var docs = Files.createDirectories(root.resolve("docs/modules/ROOT/pages"));
        Files.writeString(root.resolve("antora.yml"), "name: my-lib\ntitle: My Library\n");
        Files.writeString(docs.resolve("intro.adoc"), "= Introduction\n");
        Files.writeString(docs.resolve("usage.adoc"), "= How To Use\n");
        Files.writeString(docs.resolve("stray.md"), "# Stray\n");

        var signals = new ScanSignals();
        signals.hasAntoraConfig = true;
        signals.docFiles.add("antora.yml");
        signals.docFiles.add("docs/modules/ROOT/pages/intro.adoc");
        signals.docFiles.add("docs/modules/ROOT/pages/usage.adoc");
        signals.docFiles.add("docs/modules/ROOT/pages/stray.md");

        var index = detector.detect(root, signals);
        assertThat(index.format()).isEqualTo(Format.ANTORA);
        assertThat(index.siteName()).isEqualTo("My Library");
        assertThat(index.pages())
            .extracting(DocumentationPage::path)
            .containsExactly(
                "docs/modules/ROOT/pages/intro.adoc",
                "docs/modules/ROOT/pages/usage.adoc");
        assertThat(index.pages().get(0).format()).isEqualTo(PageFormat.ASCIIDOC);
    }

    @Test
    void plainModeIncludesMarkdownAndAsciiDocSideBySide(@TempDir Path root) throws IOException {
        var docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("guide.md"), "# Guide\n");
        Files.writeString(docs.resolve("reference.adoc"), "= Reference\n");

        var signals = new ScanSignals();
        signals.docFiles.add("docs/guide.md");
        signals.docFiles.add("docs/reference.adoc");

        var index = detector.detect(root, signals);
        assertThat(index.format()).isEqualTo(Format.PLAIN);
        assertThat(index.pages())
            .extracting(DocumentationPage::path, DocumentationPage::title, DocumentationPage::format)
            .containsExactly(
                org.assertj.core.api.Assertions.tuple("docs/guide.md", "Guide", PageFormat.MARKDOWN),
                org.assertj.core.api.Assertions.tuple("docs/reference.adoc", "Reference", PageFormat.ASCIIDOC));
    }

    @Test
    void plainModeFallsBackToHumanisedFilenameWhenHeadingAbsent(@TempDir Path root) throws IOException {
        var docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("getting-started.md"), "no heading here\n");

        var signals = new ScanSignals();
        signals.docFiles.add("docs/getting-started.md");

        var index = detector.detect(root, signals);
        assertThat(index.pages().get(0).title()).isEqualTo("Getting Started");
    }
}
