package com.acltabontabon.launchpad.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SynthesisValidatorTest {

    private final SynthesisValidator v = new SynthesisValidator();

    @Test
    void acceptsCleanProse() {
        var out = "The project benchmarks Spring Boot 4 with GraalVM Native Image across five PGO variants.";
        assertThat(v.accept(out, SynthesisValidator.Shape.PROSE, 800)).isTrue();
    }

    @Test
    void acceptsCleanBullets() {
        var out = "- POST /loan-decision processes a JSON application.\n- GET /hello is a sanity check.";
        assertThat(v.accept(out, SynthesisValidator.Shape.BULLETS, 800)).isTrue();
    }

    @Test
    void rejectsHeadings() {
        var out = "# Heading\n\nbody";
        assertThat(v.reject(out, SynthesisValidator.Shape.PROSE, 800)).contains("forbidden token");
    }

    @Test
    void rejectsMalformedStackedHeading() {
        var out = "### ## leaked heading";
        assertThat(v.reject(out, SynthesisValidator.Shape.PROSE, 800)).contains("forbidden token");
    }

    @Test
    void rejectsCodeFences() {
        var out = "```bash\nfoo\n```";
        assertThat(v.reject(out, SynthesisValidator.Shape.PROSE, 800)).contains("forbidden token");
    }

    @Test
    void rejectsRawXml() {
        var out = "Body talks about <project> tags and <dependencies>...";
        assertThat(v.reject(out, SynthesisValidator.Shape.PROSE, 800)).contains("forbidden token");
    }

    @Test
    void rejectsRawFileMentions() {
        var out = "Read the pom.xml carefully to understand.";
        assertThat(v.reject(out, SynthesisValidator.Shape.PROSE, 800)).contains("forbidden token");
    }

    @Test
    void rejectsOverlyLongOutput() {
        var out = "x".repeat(1000);
        assertThat(v.reject(out, SynthesisValidator.Shape.PROSE, 800)).contains("exceeds max-output-chars");
    }

    @Test
    void rejectsEmpty() {
        assertThat(v.reject("   \n  ", SynthesisValidator.Shape.PROSE, 800)).contains("empty");
    }

    @Test
    void rejectsInstructionLeakage() {
        var out = "Here is the opening paragraph for your project.";
        assertThat(v.reject(out, SynthesisValidator.Shape.PROSE, 800)).contains("instruction leakage");
        var sure = "Sure! Here's the paragraph.";
        assertThat(v.reject(sure, SynthesisValidator.Shape.PROSE, 800)).contains("instruction leakage");
    }

    @Test
    void rejectsBulletsInProseMode() {
        var out = "- bullet";
        assertThat(v.reject(out, SynthesisValidator.Shape.PROSE, 800)).contains("bullets");
    }

    @Test
    void rejectsProseInBulletsMode() {
        var out = "Just plain prose with no bullets at all.";
        assertThat(v.reject(out, SynthesisValidator.Shape.BULLETS, 800)).contains("bullets shape");
    }

    @Test
    void rejectsRepetitiveOutput() {
        var out = String.join("\n", "loop line", "loop line", "loop line", "loop line", "loop line");
        assertThat(v.reject(out, SynthesisValidator.Shape.PROSE, 800)).contains("repeated");
    }

    @Test
    void rejectsUnresolvedPlaceholder() {
        var out = "The project name is {{name}}.";
        assertThat(v.reject(out, SynthesisValidator.Shape.PROSE, 800)).contains("forbidden token");
    }

    @Test
    void rejectsDoubleBulletShape() {
        var out = "- `- GET /hello` invokes `HelloController.hello`\n- `- POST /loan-decision` calls decide";
        assertThat(v.reject(out, SynthesisValidator.Shape.BULLETS, 800)).contains("forbidden token");
    }

    // ── Allowlist validation ──────────────────────────────────────────────

    @Test
    void acceptsBulletsThatStayWithinAllowlist() {
        var out = "- `controller/` hosts HTTP entry points.\n- `risk/` carries scoring logic.";
        var allowed = java.util.Set.of("controller", "risk", "model");
        assertThat(v.reject(out, SynthesisValidator.Shape.BULLETS, 800, allowed)).isNull();
    }

    @Test
    void rejectsFabricatedPackageNameOutsideAllowlist() {
        // The model invented `service/` and `domain/` for a project that
        // only has `controller/`, `model/`, `risk/`. Allowlist catches it.
        var out = "- `controller/` hosts HTTP.\n- `service/` orchestrates.\n- `domain/` carries records.";
        var allowed = java.util.Set.of("controller", "model", "risk");
        var rejection = v.reject(out, SynthesisValidator.Shape.BULLETS, 800, allowed);
        assertThat(rejection).contains("out-of-allowlist");
    }

    @Test
    void rejectsFabricatedEndpointOutsideAllowlist() {
        var out = "- `POST /loan-decision` is the main route.\n- `DELETE /accounts` is invented.";
        var allowed = java.util.Set.of("POST", "GET", "/loan-decision", "/hello", "LoanDecisionController");
        assertThat(v.reject(out, SynthesisValidator.Shape.BULLETS, 800, allowed)).contains("out-of-allowlist");
    }

    @Test
    void allowlistAcceptsSubstringMatches() {
        // "controller" in the allowlist accepts "LoanDecisionController" - the
        // check is permissive enough to handle legitimate paraphrases.
        var out = "- `LoanDecisionController` decides loan outcomes.";
        var allowed = java.util.Set.of("controller", "LoanDecision");
        assertThat(v.reject(out, SynthesisValidator.Shape.BULLETS, 800, allowed)).isNull();
    }

    @Test
    void allowlistIgnoresPlainProseBackticks() {
        // Backticked phrases like `pretty status` that aren't single
        // identifiers should not trip the allowlist check.
        var out = "- `controller/` hosts a `pretty status` message.";
        var allowed = java.util.Set.of("controller");
        assertThat(v.reject(out, SynthesisValidator.Shape.BULLETS, 800, allowed)).isNull();
    }

    @Test
    void emptyAllowlistDisablesTheCheck() {
        var out = "- `whatever/` is fine.";
        assertThat(v.reject(out, SynthesisValidator.Shape.BULLETS, 800, java.util.Set.of())).isNull();
    }

    // ── LINES shape ──────────────────────────────────────────────────────────

    @Test
    void linesShapeAcceptsKeyArrowValueLines() {
        var out = "POST /loan-decision => Main workload\nGET /hello => Sanity check";
        var keys = java.util.Set.of("POST /loan-decision", "GET /hello");
        assertThat(v.reject(out, SynthesisValidator.Shape.LINES, 500, keys)).isNull();
    }

    @Test
    void linesShapeRejectsLineWithoutArrow() {
        var out = "POST /loan-decision is a thing\nGET /hello => fine";
        assertThat(v.reject(out, SynthesisValidator.Shape.LINES, 500,
            java.util.Set.of("POST /loan-decision", "GET /hello")))
            .contains("missing `=>` separator");
    }

    @Test
    void linesShapeRejectsKeyNotInAllowlist() {
        var out = "DELETE /unknown => fabricated";
        assertThat(v.reject(out, SynthesisValidator.Shape.LINES, 500,
            java.util.Set.of("GET /hello"))).contains("unknown key");
    }

    @Test
    void linesShapeRejectsLongValue() {
        var huge = "x".repeat(200);
        var out = "GET /hello => " + huge;
        assertThat(v.reject(out, SynthesisValidator.Shape.LINES, 500,
            java.util.Set.of("GET /hello"))).contains("value >");
    }

    @Test
    void linesShapeAllowsEmptyValueAfterArrow() {
        var out = "GET /actuator/health =>";
        assertThat(v.reject(out, SynthesisValidator.Shape.LINES, 500,
            java.util.Set.of("GET /actuator/health"))).isNull();
    }

    @Test
    void linesShapeRejectsWhenNoLines() {
        assertThat(v.reject("   ", SynthesisValidator.Shape.LINES, 500,
            java.util.Set.of("GET /hello"))).isNotNull();
    }

    @Test
    void linesShapeRejectsBareHandlerValue() {
        // Common parroting failure: the model copies the handler name from the
        // input as a "note". Validator catches it so the table cell stays
        // empty rather than just restating the architecture tree.
        var out = "GET /hello => HelloController.hello";
        assertThat(v.reject(out, SynthesisValidator.Shape.LINES, 500,
            java.util.Set.of("GET /hello"))).contains("bare handler");
    }
}
