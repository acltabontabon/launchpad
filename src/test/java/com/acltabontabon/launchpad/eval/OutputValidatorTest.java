package com.acltabontabon.launchpad.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.ai.OutputValidator;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.springboot.scanner.ProjectScanner;
import java.util.List;
import java.util.Map;
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
    void cleanHallucinationsStripsInventedInfraAndConfigPaths() throws Exception {
        var root = FixtureSupport.fixturePath("spring-boot");
        var ctx = ProjectScanner.forTesting().scan(root.toString(), msg -> { });

        // Each invented path uses an extension that the old fixed-allowlist regex
        // never matched (.tf, .tfvars, .kts, .ini, .env). A real fixture path is
        // mixed in to guard against over-stripping.
        String output = """
            Provision with `infra/variables.tf` and `infra/dev.tfvars`.
            Release via `gradle/scripts/release.kts`; tune `config/app.ini` and `config/.env`.
            The real service is `src/main/java/com/example/users/UserService.java`.
            """;

        var clean = validator.cleanHallucinations(output, ctx);
        assertThat(clean.strippedCount()).isEqualTo(5);
        assertThat(clean.content())
            .doesNotContain("variables.tf")
            .doesNotContain("dev.tfvars")
            .doesNotContain("release.kts")
            .doesNotContain("app.ini")
            .doesNotContain("config/.env");
        assertThat(clean.content()).contains("UserService.java");
    }

    @Test
    void cleanHallucinationsRecognizesProjectDerivedExtensions() {
        // `.fooext` is in no static allowlist - it is recognized only because the
        // scanned project lists a real source file with that extension.
        var ctx = new ProjectContext(
            "demo", "/tmp", StackProfile.unknown(),
            List.of("schema/model.fooext"),
            List.of(), Map.of(), List.of(), Map.of(), List.of(), null);

        String output = """
            The schema lives in `schema/model.fooext`.
            But `docs/missing.fooext` does not exist.
            """;

        var clean = validator.cleanHallucinations(output, ctx);
        assertThat(clean.strippedCount()).isEqualTo(1);
        assertThat(clean.content()).contains("schema/model.fooext");
        assertThat(clean.content()).doesNotContain("docs/missing.fooext");
    }

    @Test
    void cleanHallucinationsStillStripsAlreadySupportedExtensions() throws Exception {
        // Regression guard: .go / .yaml already matched before this change.
        var root = FixtureSupport.fixturePath("spring-boot");
        var ctx = ProjectScanner.forTesting().scan(root.toString(), msg -> { });

        String output = """
            See `cmd/server/main.go` and `config/app.yaml` for the invented bits.
            """;

        var clean = validator.cleanHallucinations(output, ctx);
        assertThat(clean.strippedCount()).isEqualTo(2);
        assertThat(clean.content())
            .doesNotContain("main.go")
            .doesNotContain("app.yaml");
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
