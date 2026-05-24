package com.acltabontabon.launchpad.scanner.doc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PurposeClassifierTest {

    private final PurposeClassifier classifier = PurposeClassifier.deterministicOnly();

    static Stream<Arguments> paths() {
        return Stream.of(
            // OVERVIEW
            Arguments.of("README.md", Purpose.OVERVIEW),
            Arguments.of("readme.adoc", Purpose.OVERVIEW),
            Arguments.of("docs/index.md", Purpose.OVERVIEW),
            Arguments.of("docs/overview.md", Purpose.OVERVIEW),
            Arguments.of("docs/about.md", Purpose.OVERVIEW),
            Arguments.of("docs/intro.adoc", Purpose.OVERVIEW),
            Arguments.of("docs/introduction.md", Purpose.OVERVIEW),
            // SETUP
            Arguments.of("INSTALL.md", Purpose.SETUP),
            Arguments.of("docs/installation.md", Purpose.SETUP),
            Arguments.of("docs/setup.md", Purpose.SETUP),
            Arguments.of("docs/getting-started.md", Purpose.SETUP),
            Arguments.of("docs/getting_started.adoc", Purpose.SETUP),
            Arguments.of("docs/quickstart.md", Purpose.SETUP),
            Arguments.of("docs/quick-start.md", Purpose.SETUP),
            // ARCHITECTURE
            Arguments.of("docs/architecture.md", Purpose.ARCHITECTURE),
            Arguments.of("docs/design.adoc", Purpose.ARCHITECTURE),
            Arguments.of("docs/adr/0001-use-spring.md", Purpose.ARCHITECTURE),
            Arguments.of("docs/adrs/0002-pick-postgres.md", Purpose.ARCHITECTURE),
            Arguments.of("docs/hld.md", Purpose.ARCHITECTURE),
            Arguments.of("docs/lld.adoc", Purpose.ARCHITECTURE),
            // API
            Arguments.of("docs/api/users.md", Purpose.API),
            Arguments.of("docs/reference.md", Purpose.API),
            Arguments.of("docs/openapi.md", Purpose.API),
            Arguments.of("docs/swagger.adoc", Purpose.API),
            Arguments.of("docs/endpoints.md", Purpose.API),
            // OPERATIONS
            Arguments.of("docs/operations.md", Purpose.OPERATIONS),
            Arguments.of("docs/ops/runbook.md", Purpose.OPERATIONS),
            Arguments.of("docs/runbook.adoc", Purpose.OPERATIONS),
            Arguments.of("docs/deploy.md", Purpose.OPERATIONS),
            Arguments.of("docs/deployment.md", Purpose.OPERATIONS),
            Arguments.of("docs/monitoring.adoc", Purpose.OPERATIONS),
            Arguments.of("docs/observability.md", Purpose.OPERATIONS),
            // CONTRIBUTION
            Arguments.of("CONTRIBUTING.md", Purpose.CONTRIBUTION),
            Arguments.of("docs/contribute.adoc", Purpose.CONTRIBUTION),
            Arguments.of("docs/development.md", Purpose.CONTRIBUTION),
            Arguments.of("docs/dev-guide.md", Purpose.CONTRIBUTION),
            // CHANGELOG
            Arguments.of("CHANGELOG.md", Purpose.CHANGELOG),
            Arguments.of("docs/history.md", Purpose.CHANGELOG),
            Arguments.of("docs/release-notes.md", Purpose.CHANGELOG),
            Arguments.of("docs/releases.adoc", Purpose.CHANGELOG),
            // UNKNOWN
            Arguments.of("docs/random-thoughts.md", Purpose.UNKNOWN),
            Arguments.of("docs/notes/2026-05-24.md", Purpose.UNKNOWN)
        );
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("paths")
    void classifiesByPath(String path, Purpose expected) {
        assertThat(classifier.classifyByPath(path)).isEqualTo(expected);
    }

    @Test
    void nullOrBlankPathIsUnknown() {
        assertThat(classifier.classifyByPath(null)).isEqualTo(Purpose.UNKNOWN);
        assertThat(classifier.classifyByPath("")).isEqualTo(Purpose.UNKNOWN);
        assertThat(classifier.classifyByPath("   ")).isEqualTo(Purpose.UNKNOWN);
    }

    @Test
    void unknownFallsBackToUnknownWhenAiDisabled() {
        // No AI fallback wired in - classify() must not attempt anything and
        // must return UNKNOWN.
        var p = classifier.classify("docs/random-thoughts.md", () -> "irrelevant body");
        assertThat(p).isEqualTo(Purpose.UNKNOWN);
    }
}
