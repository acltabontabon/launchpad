package com.acltabontabon.launchpad.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.acltabontabon.launchpad.ai.ContextGeneratorService;
import com.acltabontabon.launchpad.ai.ProviderHealthChecker;
import com.acltabontabon.launchpad.scanner.ProjectScanner;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Full-pipeline eval that hits a real local Ollama. Gated by the
 * "launchpad.eval" system property so it stays out of default test runs
 * (enabled via -Peval). For each fixture: runs the scanner, asks the LLM
 * for a summary, asserts the prompt-driven format markers are present and
 * substring keywords appear at least once.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "launchpad.ai.provider=ollama",
    "launchpad.ai.base-url=${LAUNCHPAD_EVAL_OLLAMA_URL:http://localhost:11434}",
    "launchpad.ai.model=${LAUNCHPAD_EVAL_MODEL:qwen2.5-coder:7b}",
    "spring.ai.ollama.base-url=${LAUNCHPAD_EVAL_OLLAMA_URL:http://localhost:11434}",
    "spring.ai.ollama.chat.options.model=${LAUNCHPAD_EVAL_MODEL:qwen2.5-coder:7b}"
})
class GenerationEvalIT {

    @Autowired
    private ContextGeneratorService generator;

    @Autowired
    private ProviderHealthChecker healthChecker;

    @BeforeEach
    void assumeEvalEnabled() {
        assumeTrue(Boolean.getBoolean("launchpad.eval"),
            "Set -Dlaunchpad.eval=true (or use -Peval) to run generation evals");
        var status = healthChecker.check();
        assumeTrue(status.isReady(), "Ollama not ready: " + status.message());
    }

    static Stream<Arguments> fixtures() {
        return ProjectFixture.all().stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void summaryContainsExpectedKeywords(ProjectFixture fixture) throws Exception {
        var root = FixtureSupport.fixturePath(fixture.classpathDir());
        var ctx = ProjectScanner.forTesting().scan(root.toString(), msg -> { });

        var output = generator.generateProjectSummary(ctx, chunk -> { });

        assertThat(output.content())
            .as("summary length for %s", fixture.name())
            .hasSizeGreaterThan(200);
        assertThat(output.content()).contains("## Overview");
        assertThat(output.content()).contains("## Architecture");

        for (var keyword : fixture.expectedSummaryKeywords()) {
            assertThat(output.content().toLowerCase())
                .as("expected keyword %s present in %s summary", keyword, fixture.name())
                .contains(keyword.toLowerCase());
        }
    }
}
