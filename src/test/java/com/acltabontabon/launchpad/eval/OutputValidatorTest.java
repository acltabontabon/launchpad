package com.acltabontabon.launchpad.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.ai.OutputValidator;
import com.acltabontabon.launchpad.springboot.scanner.ProjectScanner;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link OutputValidator} against real scanned context. Confirms
 * that structural failures surface as warnings and that hallucinated path
 * references are stripped (not double-reported) by {@link OutputValidator#cleanHallucinations}.
 */
class OutputValidatorTest {

    private final OutputValidator validator = new OutputValidator();

    @Test
    void flagsMissingSectionsButNotHallucinatedPaths() throws Exception {
        var root = FixtureSupport.fixturePath("spring-boot");
        var ctx = ProjectScanner.forTesting().scan(root.toString(), msg -> { });

        String output = """
            Some text but no required headings.

            The user logic lives in `src/main/java/com/example/users/UserService.java` (real).
            See also `src/main/java/com/example/completely/Made/Up.java` for the made-up reference.
            """;

        List<String> warnings = validator.validate(output, ctx,
            List.of("## Overview", "## Architecture"));

        assertThat(warnings).anyMatch(w -> w.contains("## Overview"));
        assertThat(warnings).anyMatch(w -> w.contains("## Architecture"));
        // Hallucination handling is the cleaner's job - validate() must not duplicate it.
        assertThat(warnings).noneMatch(w -> w.contains("Made/Up.java"));
        assertThat(warnings).noneMatch(w -> w.contains("UserService.java"));
    }

    @Test
    void cleanHallucinationsStripsInventedPaths() throws Exception {
        var root = FixtureSupport.fixturePath("spring-boot");
        var ctx = ProjectScanner.forTesting().scan(root.toString(), msg -> { });

        String output = """
            See `src/main/java/com/example/users/UserService.java` for the real thing.
            And `src/main/java/com/example/completely/Made/Up.java` is invented.
            """;

        var clean = validator.cleanHallucinations(output, ctx);
        assertThat(clean.strippedCount()).isEqualTo(1);
        assertThat(clean.content()).doesNotContain("Made/Up.java");
        assertThat(clean.content()).contains("UserService.java");
    }

    @Test
    void cleanHallucinationsAllowsSpringProfileConfigConvention() throws Exception {
        var root = FixtureSupport.fixturePath("spring-boot");
        var ctx = ProjectScanner.forTesting().scan(root.toString(), msg -> { });

        String output = """
            Dev overrides live in `application-dev.yml` and prod in `application-prod.properties`.
            """;

        var clean = validator.cleanHallucinations(output, ctx);
        assertThat(clean.strippedCount()).isZero();
        assertThat(clean.content()).contains("application-dev.yml");
        assertThat(clean.content()).contains("application-prod.properties");
    }

    @Test
    void passesWhenAllSectionsPresentAndPathsReal() throws Exception {
        var root = FixtureSupport.fixturePath("spring-boot");
        var ctx = ProjectScanner.forTesting().scan(root.toString(), msg -> { });

        String output = """
            ## Overview
            A Spring Boot users API.

            ## Architecture
            Layered: controller, service, repository in `src/main/java/com/example/users/UserService.java`.
            """;

        var warnings = validator.validate(output, ctx,
            List.of("## Overview", "## Architecture"));
        assertThat(warnings).isEmpty();
    }
}
