package com.acltabontabon.launchpad.scanner.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.doc.DocumentationPage.PageFormat;
import org.junit.jupiter.api.Test;

class DocTitleExtractorTest {

    @Test
    void extractsMarkdownH1() {
        var content = "# Getting Started\n\nWelcome to the project.\n## Sub heading\n";
        assertThat(DocTitleExtractor.extract("intro.md", PageFormat.MARKDOWN, content))
            .isEqualTo("Getting Started");
    }

    @Test
    void ignoresMarkdownH2WhenLookingForH1() {
        var content = "## Section\nNot the document title.\n";
        assertThat(DocTitleExtractor.extract("section.md", PageFormat.MARKDOWN, content))
            .isEqualTo("Section");
    }

    @Test
    void stripsTrailingHashesFromMarkdownHeading() {
        var content = "# Intro ###\n";
        assertThat(DocTitleExtractor.extract("intro.md", PageFormat.MARKDOWN, content))
            .isEqualTo("Intro");
    }

    @Test
    void extractsAsciiDocDocumentTitle() {
        var content = "= My Library\n:doctype: book\n\n== First Section\n";
        assertThat(DocTitleExtractor.extract("index.adoc", PageFormat.ASCIIDOC, content))
            .isEqualTo("My Library");
    }

    @Test
    void ignoresAsciiDocSectionHeadingsWhenNoDocumentTitle() {
        var content = "== Just a section\nContent.\n";
        assertThat(DocTitleExtractor.extract("just-a-section.adoc", PageFormat.ASCIIDOC, content))
            .isEqualTo("Just A Section");
    }

    @Test
    void extractsRstUnderlinedHeading() {
        var content = "Configuration Guide\n===================\n\nText here.\n";
        assertThat(DocTitleExtractor.extract("config.rst", PageFormat.RST, content))
            .isEqualTo("Configuration Guide");
    }

    @Test
    void rstUnderlineMustBeAtLeastAsLongAsTitle() {
        var content = "Long Title\n===\n";
        assertThat(DocTitleExtractor.extract("long-title.rst", PageFormat.RST, content))
            .isEqualTo("Long Title");
    }

    @Test
    void fallsBackToHumanisedStemWhenNoHeadingPresent() {
        assertThat(DocTitleExtractor.extract("getting-started.md", PageFormat.MARKDOWN, "no heading here"))
            .isEqualTo("Getting Started");
    }

    @Test
    void humanisesUnderscoredFileStem() {
        assertThat(DocTitleExtractor.humaniseStem("api_reference.md"))
            .isEqualTo("Api Reference");
    }

    @Test
    void handlesEmptyContent() {
        assertThat(DocTitleExtractor.extract("readme.md", PageFormat.MARKDOWN, ""))
            .isEqualTo("Readme");
    }
}
