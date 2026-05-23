package com.acltabontabon.launchpad.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.ProjectScanner;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Scanner-side assertions that do not require Ollama. Always runs as part of
 * `./mvnw test`. If any fixture's expected facts drift from reality, this
 * fails loudly and the eval harness has done its job.
 */
class ScannerEvalTest {

    private final ProjectScanner scanner = ProjectScanner.forTesting();

    static Stream<Arguments> fixtures() {
        return ProjectFixture.all().stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void scannerProducesExpectedFacts(ProjectFixture fixture) throws Exception {
        var root = FixtureSupport.fixturePath(fixture.classpathDir());
        ProjectContext ctx = scanner.scan(root.toString(), msg -> { });

        assertThat(ctx.stack().language())
            .as("language for %s", fixture.name())
            .isEqualTo(fixture.expectedLanguage());
        assertThat(ctx.stack().buildTool())
            .as("buildTool for %s", fixture.name())
            .isEqualTo(fixture.expectedBuildTool());
        assertThat(ctx.stack().framework())
            .as("framework for %s", fixture.name())
            .isEqualTo(fixture.expectedFramework());

        if (fixture.expectedEntryPointBasename() != null) {
            assertThat(ctx.entryPoints())
                .as("entry points for %s", fixture.name())
                .isNotEmpty();
            var main = ctx.entryPoints().get("main");
            assertThat(main)
                .as("main entry point for %s", fixture.name())
                .isNotNull()
                .endsWith(fixture.expectedEntryPointBasename());
        }

        switch (fixture.name()) {
            case "spring-boot" -> {
                var sp = ctx.stack().springProfile();
                assertThat(sp).as("SpringProfile populated for Spring app fixture").isNotNull();
                assertThat(sp.facets())
                    .as("expected facets for the standard Spring Boot app fixture")
                    .contains("web-mvc", "persistence-jpa");
                assertThat(sp.starterLibrary())
                    .as("regular Spring Boot app MUST NOT be misclassified as a starter library")
                    .isFalse();
                assertThat(sp.facets())
                    .as("starter-library facet MUST NOT appear for the app fixture")
                    .doesNotContain("starter-library");
                assertThat(ctx.stack().databricksProfile())
                    .as("Spring app MUST NOT be misclassified as Databricks")
                    .isNull();
            }
            case "spring-boot-starter" -> {
                var sp = ctx.stack().springProfile();
                assertThat(sp).as("SpringProfile populated for starter library fixture").isNotNull();
                assertThat(sp.starterLibrary())
                    .as("starter library fixture must be detected as a library")
                    .isTrue();
                assertThat(sp.facets())
                    .as("starter-library facet must appear first")
                    .startsWith("starter-library");
                assertThat(ctx.stack().databricksProfile())
                    .as("Spring starter library MUST NOT be misclassified as Databricks")
                    .isNull();
            }
            case "databricks-recon" -> {
                assertThat(ctx.stack().framework())
                    .as("databricks-recon fixture must be detected as Databricks")
                    .isEqualTo("Databricks");
                var dp = ctx.stack().databricksProfile();
                assertThat(dp).as("DatabricksProfile populated for recon fixture").isNotNull();
                assertThat(dp.terraform()).as("terraform signal").isTrue();
                assertThat(dp.dlt()).as("dlt signal").isTrue();
                assertThat(dp.python()).as("python signal").isTrue();
                assertThat(dp.sql()).as("sql signal").isTrue();
                assertThat(dp.facets())
                    .as("databricks facet ordering")
                    .containsExactly("terraform-deployment", "dlt-pipeline",
                        "python-source", "sql-source");
                assertThat(ctx.stack().springProfile())
                    .as("Databricks project MUST NOT carry a SpringProfile")
                    .isNull();
            }
            default -> {
                assertThat(ctx.stack().springProfile())
                    .as("non-Spring fixtures must not carry a SpringProfile")
                    .isNull();
                assertThat(ctx.stack().databricksProfile())
                    .as("non-Databricks fixtures must not carry a DatabricksProfile")
                    .isNull();
                assertThat(ctx.stack().framework())
                    .as("non-Databricks fixtures MUST NOT be misclassified as Databricks")
                    .isNotEqualTo("Databricks");
            }
        }

        var depNames = ctx.dependencies().stream().map(d -> d.name()).toList();
        for (var expected : fixture.expectedDepNames()) {
            assertThat(depNames)
                .as("expected dep %s present in %s", expected, fixture.name())
                .anyMatch(actual -> actual.equals(expected) || actual.endsWith(":" + expected));
        }

        if (!fixture.expectedSymbolHints().isEmpty()) {
            var allSymbols = ctx.packageSummaries().stream()
                .flatMap(p -> p.sampleSymbols().stream())
                .toList();
            for (var hint : fixture.expectedSymbolHints()) {
                assertThat(allSymbols)
                    .as("expected symbol %s present in %s package summaries", hint, fixture.name())
                    .contains(hint);
            }
        }

        // toPromptString stays within budget and doesn't dump raw source list.
        var prompt = ctx.toPromptString();
        assertThat(prompt.length()).isLessThanOrEqualTo(8_500);
        assertThat(prompt).contains("Stack:");
        assertThat(prompt).contains("## Source Structure");
        // Sanity: the prompt must not contain every source file path - that was the old failure.
        assertThat(prompt.lines().filter(l -> l.startsWith("- ")).count())
            .as("prompt bullets capped (no flat file dump) for %s", fixture.name())
            .isLessThan(80);
    }
}
