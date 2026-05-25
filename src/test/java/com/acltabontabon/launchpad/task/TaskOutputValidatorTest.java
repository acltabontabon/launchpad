package com.acltabontabon.launchpad.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskOutputValidatorTest {

    private final TaskOutputValidator validator = new TaskOutputValidator();

    @Test
    void emptyOutputProducesEmptyWarning() {
        assertThat(validator.validate(null)).contains("synthesised prompt was empty");
        assertThat(validator.validate("")).contains("synthesised prompt was empty");
        assertThat(validator.validate("   \n\n")).contains("synthesised prompt was empty");
    }

    @Test
    void missingGoalHeadingWarns() {
        var md = """
            ## Constraints
            - [must] do the thing.
            ## Acceptance criteria
            - it works
            """;
        assertThat(validator.validate(md)).contains("missing required section: ## Goal");
    }

    @Test
    void missingConstraintsHeadingWarns() {
        var md = """
            ## Goal
            Do the thing.
            ## Acceptance criteria
            - it works
            """;
        assertThat(validator.validate(md)).contains("missing required section: ## Constraints");
    }

    @Test
    void emptyGoalBodyWarns() {
        var md = """
            ## Goal

            ## Constraints
            - [must] something.
            ## Acceptance criteria
            - it works
            """;
        assertThat(validator.validate(md)).contains("Goal section is empty");
    }

    @Test
    void acceptanceWithoutBulletWarns() {
        var md = """
            ## Goal
            Do the thing.
            ## Constraints
            - [must] something.
            ## Acceptance criteria
            free-form prose with no bullet
            """;
        assertThat(validator.validate(md)).contains("Acceptance criteria section has no bullets");
    }

    @Test
    void quoteOnlyBulletWarns() {
        var md = """
            ## Goal
            Do the thing.
            ## Constraints
            - [must] something.
            ## Acceptance criteria
            - "literal user quote"
            """;
        assertThat(validator.validate(md)).contains("Acceptance criteria contains quote-only bullets");
    }

    @Test
    void placeholderLeakWarns() {
        var md = """
            ## Goal
            Do the <TBD> thing.
            ## Constraints
            - [must] something.
            ## Acceptance criteria
            - works
            """;
        assertThat(validator.validate(md)).contains("output contains unresolved placeholder tokens");
    }

    @Test
    void mustachePlaceholderWarns() {
        var md = """
            ## Goal
            Do {{the_thing}} please.
            ## Constraints
            - [must] something.
            ## Acceptance criteria
            - works
            """;
        assertThat(validator.validate(md)).contains("output contains unresolved placeholder tokens");
    }

    @Test
    void wellFormedOutputProducesNoWarnings() {
        var md = """
            ## Goal
            Create a greeting API that returns a name.

            ## Constraints
            - [must] Apply authentication.  *(auth-rule)*

            ## Acceptance criteria
            - The API responds to GET /v1/greet.
            - The response contains a name field.

            ## Out of scope
            - Rate limiting.
            """;
        assertThat(validator.validate(md)).isEmpty();
    }

    @Test
    void sectionBodyExtractsBetweenHeadings() {
        var md = "## Goal\nThe goal body.\n\n## Constraints\n- a rule.\n";
        assertThat(TaskOutputValidator.sectionBody(md, "## Goal")).isEqualTo("The goal body.");
        assertThat(TaskOutputValidator.sectionBody(md, "## Constraints")).isEqualTo("- a rule.");
    }

    @Test
    void sectionBodyReturnsEmptyForMissingHeading() {
        var md = "## Goal\nbody\n";
        assertThat(TaskOutputValidator.sectionBody(md, "## Acceptance criteria")).isEmpty();
    }

    @Test
    void shortGoalIsFlaggedAsTooShortToBeActionable() {
        var md = """
            ## Goal
            Build the feature.

            ## Constraints
            - [must] something.
            ## Acceptance criteria
            - The endpoint responds with the expected payload.
            """;
        var warnings = validator.validate(md);
        assertThat(warnings).contains("Goal section is too short to be actionable");
        assertThat(warnings).doesNotContain("Goal section is empty");
    }

    @Test
    void goalAtOrAboveWordFloorIsAccepted() {
        var md = """
            ## Goal
            Create a greeting API that returns a name.

            ## Constraints
            - [must] something.
            ## Acceptance criteria
            - The endpoint responds with the expected payload.
            """;
        assertThat(validator.validate(md))
            .doesNotContain("Goal section is too short to be actionable");
    }

    @Test
    void fillerAcceptanceBulletsAreFlagged() {
        var md = """
            ## Goal
            Create a greeting API that returns a name.

            ## Constraints
            - [must] something.
            ## Acceptance criteria
            - Works correctly.
            - The response contains a name field.
            """;
        assertThat(validator.validate(md))
            .contains("Acceptance criteria contains vague filler bullets");
    }

    @Test
    void deterministicFallbackBulletStandingAloneIsNotFlagged() {
        // The assembler emits this exact line as a safety net when the model
        // produces no usable acceptance criteria. It is intentional, not LLM
        // filler, and must not trip the filler / short-bullet checks.
        var md = """
            ## Goal
            Create a greeting API that returns a name.

            ## Constraints
            - [must] something.
            ## Acceptance criteria
            - Behaviour described in the Goal section is implemented.
            """;
        var warnings = validator.validate(md);
        assertThat(warnings).doesNotContain("Acceptance criteria contains vague filler bullets");
        assertThat(warnings).doesNotContain("Acceptance criteria has bullets shorter than 3 words");
    }

    @Test
    void shortAcceptanceBulletsAreFlagged() {
        var md = """
            ## Goal
            Create a greeting API that returns a name.

            ## Constraints
            - [must] something.
            ## Acceptance criteria
            - Done.
            - The response contains a name field.
            """;
        assertThat(validator.validate(md))
            .contains("Acceptance criteria has bullets shorter than 3 words");
    }
}
