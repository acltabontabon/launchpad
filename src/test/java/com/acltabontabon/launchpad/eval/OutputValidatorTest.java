package com.acltabontabon.launchpad.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.ai.OutputValidator;
import com.acltabontabon.launchpad.scanner.ProjectScanner;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link OutputValidator} against real scanned context. Confirms
 * that hallucinated path references are flagged and that real ones are not.
 */
class OutputValidatorTest {

    private final OutputValidator validator = new OutputValidator();

    @Test
    void flagsMissingSectionAndHallucinatedPath() throws Exception {
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
        assertThat(warnings).anyMatch(w -> w.contains("Made/Up.java"));
        assertThat(warnings).noneMatch(w -> w.contains("UserService.java"));
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
