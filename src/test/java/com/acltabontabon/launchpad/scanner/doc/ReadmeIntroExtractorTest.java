package com.acltabontabon.launchpad.scanner.doc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadmeIntroExtractorTest {

    @Test
    void extractsUpToTwoProseParagraphsAfterTitle() {
        var readme = """
            # my-project

            A reproducible benchmark of Spring Boot 4 + GraalVM Native Image.

            More content follows here.

            A third paragraph that should be ignored.
            """;
        var intro = ReadmeIntroExtractor.extract(readme);
        assertThat(intro).isEqualTo(
            "A reproducible benchmark of Spring Boot 4 + GraalVM Native Image."
                + "\n\nMore content follows here.");
    }

    @Test
    void treatsBlockquoteAsParagraphContinuationForColonLeadIn() {
        var readme = """
            # nativeimage-demo

            A reproducible benchmark of Spring Boot 4 - built to answer one question:

            > Should we actually adopt native image for our services?
            """;
        var intro = ReadmeIntroExtractor.extract(readme);
        assertThat(intro).contains("built to answer one question:");
        assertThat(intro).contains("Should we actually adopt native image for our services?");
    }

    @Test
    void skipsBadgesAndImages() {
        var readme = """
            # my-project

            ![build](https://example.com/badge.svg)
            ![coverage](https://example.com/coverage.svg)

            This is the real intro paragraph.
            """;
        var intro = ReadmeIntroExtractor.extract(readme);
        assertThat(intro).isEqualTo("This is the real intro paragraph.");
    }

    @Test
    void skipsBulletListAfterTitle() {
        var readme = """
            # my-project

            - bullet a
            - bullet b

            Real prose paragraph here.
            """;
        var intro = ReadmeIntroExtractor.extract(readme);
        assertThat(intro).isEqualTo("Real prose paragraph here.");
    }

    @Test
    void stopsAtNextHeading() {
        var readme = """
            # my-project

            First paragraph line one.
            First paragraph line two.

            ## Section heading

            Second paragraph that should not appear.
            """;
        var intro = ReadmeIntroExtractor.extract(readme);
        assertThat(intro).isEqualTo("First paragraph line one. First paragraph line two.");
    }

    @Test
    void skipsHorizontalRules() {
        var readme = """
            # my-project

            ---

            This paragraph survives.
            """;
        var intro = ReadmeIntroExtractor.extract(readme);
        assertThat(intro).isEqualTo("This paragraph survives.");
    }

    @Test
    void returnsEmptyWhenNoUsefulParagraph() {
        var readme = """
            # my-project

            - just a bullet list
            - and another
            """;
        assertThat(ReadmeIntroExtractor.extract(readme)).isEmpty();
    }

    @Test
    void skipsFrontMatter() {
        var readme = """
            ---
            title: x
            ---

            # my-project

            Real paragraph.
            """;
        assertThat(ReadmeIntroExtractor.extract(readme)).isEqualTo("Real paragraph.");
    }

    @Test
    void skipsCodeFences() {
        var readme = """
            # my-project

            ```
            this is a code block
            ```

            Real paragraph after fence.
            """;
        assertThat(ReadmeIntroExtractor.extract(readme)).isEqualTo("Real paragraph after fence.");
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertThat(ReadmeIntroExtractor.extract(null)).isEmpty();
        assertThat(ReadmeIntroExtractor.extract("")).isEmpty();
        assertThat(ReadmeIntroExtractor.extract("\n\n  \n")).isEmpty();
    }
}
