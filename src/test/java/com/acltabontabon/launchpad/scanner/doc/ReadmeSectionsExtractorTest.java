package com.acltabontabon.launchpad.scanner.doc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadmeSectionsExtractorTest {

    @Test
    void extractsQuickStartAndUsageSections() {
        var readme = """
            # my-project

            Intro paragraph here.

            ## Quick Start

            ```bash
            ./mvnw spring-boot:run
            ```

            ## Usage

            Hit the /loan-decision endpoint with a JSON body.

            ## Architecture

            irrelevant section
            """;
        var sections = ReadmeSectionsExtractor.extract(readme);
        assertThat(sections).containsKeys("quick start", "usage");
        assertThat(sections.get("quick start")).contains("./mvnw spring-boot:run");
        assertThat(sections.get("usage")).contains("/loan-decision");
        assertThat(sections).doesNotContainKey("architecture");
    }

    @Test
    void capturesCommandsSectionWithColonHeading() {
        var readme = """
            ## Commands

            - run: `./mvnw spring-boot:run`
            - test: `./mvnw test`
            """;
        var sections = ReadmeSectionsExtractor.extract(readme);
        assertThat(sections).containsKey("commands");
        assertThat(sections.get("commands")).contains("./mvnw spring-boot:run");
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertThat(ReadmeSectionsExtractor.extract(null)).isEmpty();
        assertThat(ReadmeSectionsExtractor.extract("")).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoRecognisedSection() {
        var readme = """
            # title

            ## Architecture
            stuff

            ## License
            MIT
            """;
        assertThat(ReadmeSectionsExtractor.extract(readme)).isEmpty();
    }
}
