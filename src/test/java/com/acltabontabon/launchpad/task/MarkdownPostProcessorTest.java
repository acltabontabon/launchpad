package com.acltabontabon.launchpad.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.task.MarkdownPostProcessor.FinalizeSections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MarkdownPostProcessor}: marker parsing, the goal/acceptance
 * grounding stages, out-of-scope grounding, and the deterministic final-doc
 * assembly with cap, severity sort, and null-task safety.
 */
class MarkdownPostProcessorTest {

    @Nested
    class ParseMarkerSections {

        @Test
        void parsesStandardEqualsDelimiters() {
            var raw = """
                ===GOAL===
                Goal text here.
                ===ACCEPTANCE===
                - one
                - two
                ===OUT_OF_SCOPE===
                - excluded
                """;
            var sections = MarkdownPostProcessor.parseMarkerSections(raw);
            assertThat(sections.goal()).isEqualTo("Goal text here.");
            assertThat(sections.acceptance()).isEqualTo("- one\n- two");
            assertThat(sections.outOfScope()).isEqualTo("- excluded");
        }

        @Test
        void parsesAsymmetricDelimiters() {
            var raw = """
                ===GOAL---
                Goal text.
                ---ACCEPTANCE===
                - accept
                ===OUT_OF_SCOPE---
                - excluded
                """;
            var sections = MarkdownPostProcessor.parseMarkerSections(raw);
            assertThat(sections.goal()).isEqualTo("Goal text.");
            assertThat(sections.acceptance()).isEqualTo("- accept");
            assertThat(sections.outOfScope()).isEqualTo("- excluded");
        }

        @Test
        void missingSectionsBecomeEmptyStrings() {
            var raw = "===GOAL===\nJust a goal.";
            var sections = MarkdownPostProcessor.parseMarkerSections(raw);
            assertThat(sections.goal()).isEqualTo("Just a goal.");
            assertThat(sections.acceptance()).isEmpty();
            assertThat(sections.outOfScope()).isEmpty();
        }
    }

    @Nested
    class GroundOutOfScope {

        @Test
        void clearsSectionWhenNoUserOptOuts() {
            var sections = new FinalizeSections("goal", "- accept", "- some bullet");
            var result = MarkdownPostProcessor.groundOutOfScope(sections, List.of(), Set.of(), List.of());
            assertThat(result.outOfScope()).isEmpty();
        }

        @Test
        void keepsBulletsGroundedInNegatedQuestion() {
            var sections = new FinalizeSections("goal", "- accept",
                "- Adding authentication to this endpoint.\n- Adding concurrent request support.");
            var history = List.of(
                new TaskTurn("Should authentication be required?", "no need"));
            var rules = List.of(rule("auth", "Authentication Required by Default"));
            var optedOut = Set.of("auth");
            var result = MarkdownPostProcessor.groundOutOfScope(sections, history, optedOut, rules);
            assertThat(result.outOfScope()).contains("authentication");
            assertThat(result.outOfScope()).doesNotContain("concurrent");
        }
    }

    @Nested
    class GroundAcceptance {

        @Test
        void dropsQuoteOnlyBullets() {
            var sections = new FinalizeSections("goal",
                "- \"create new api\"\n- The implementation matches the task description.",
                "");
            var result = MarkdownPostProcessor.groundAcceptance(sections, "create new api", List.of());
            assertThat(result.acceptance()).doesNotContain("\"create new api\"");
            assertThat(result.acceptance()).contains("matches the task description");
        }

        @Test
        void dropsBulletsWithNoUserWordOverlap() {
            var sections = new FinalizeSections("goal",
                "- Adds support for internationalization.\n- The API returns a greeting message.",
                "");
            var result = MarkdownPostProcessor.groundAcceptance(sections,
                "create greeting api",
                List.of(new TaskTurn("What does it do?", "return a greeting message")));
            assertThat(result.acceptance()).doesNotContain("internationalization");
            assertThat(result.acceptance()).contains("greeting message");
        }

        @Test
        void keepsFallbackBulletByWhitelist() {
            var sections = new FinalizeSections("goal",
                "- The implementation matches the task description and any constraints in the parent prompt.",
                "");
            var result = MarkdownPostProcessor.groundAcceptance(sections,
                "completely unrelated user words", List.of());
            assertThat(result.acceptance()).contains("matches the task description");
        }
    }

    @Nested
    class SeparateGoalProseFromBullets {

        @Test
        void passesThroughPureProseGoal() {
            var sections = new FinalizeSections(
                "Create a greeting API that returns a name.", "- accept bullet", "");
            var result = MarkdownPostProcessor.separateGoalProseFromBullets(sections);
            assertThat(result.goal()).isEqualTo("Create a greeting API that returns a name.");
            assertThat(result.acceptance()).isEqualTo("- accept bullet");
        }

        @Test
        void movesTrailingGoalBulletsToAcceptance() {
            var goalWithBullets = """
                Create a new greeting API.

                - The API responds to GET.
                - Returns a JSON greeting.
                """;
            var sections = new FinalizeSections(goalWithBullets, "- existing accept", "");
            var result = MarkdownPostProcessor.separateGoalProseFromBullets(sections);
            assertThat(result.goal()).isEqualTo("Create a new greeting API.");
            assertThat(result.acceptance())
                .contains("The API responds to GET")
                .contains("Returns a JSON greeting")
                .contains("existing accept");
        }
    }

    @Nested
    class AssembleFinalMarkdown {

        @Test
        void producesAllSectionsInOrder() {
            var sections = new FinalizeSections("Create a new API.", "- accepts a name", "");
            var rules = List.of(ruleWithDesc("rule1", "must", "Some directive text."));
            var doc = MarkdownPostProcessor.assembleFinalMarkdown(
                "create new api", sections, rules, List.of(), List.of());
            assertThat(doc)
                .contains("## Goal")
                .contains("Create a new API.")
                .contains("## Constraints")
                .contains("[must] Some directive text.")
                .contains("(rule1)")
                .contains("## Acceptance criteria")
                .contains("accepts a name");
        }

        @Test
        void omitsOutOfScopeSectionWhenEmpty() {
            var sections = new FinalizeSections("goal", "- a", "");
            var doc = MarkdownPostProcessor.assembleFinalMarkdown(
                "task", sections, List.of(), List.of(), List.of());
            assertThat(doc).doesNotContain("## Out of scope");
        }

        @Test
        void includesOutOfScopeSectionWhenNonEmpty() {
            var sections = new FinalizeSections("goal", "- a", "- excluded thing");
            var doc = MarkdownPostProcessor.assembleFinalMarkdown(
                "task", sections, List.of(), List.of(), List.of());
            assertThat(doc).contains("## Out of scope").contains("excluded thing");
        }

        @Test
        void capsConstraintsWithOverflowFooter() {
            var rules = new java.util.ArrayList<Rule>();
            for (int i = 1; i <= 12; i++) rules.add(mustRule("rule-" + i, "Rule " + i));
            var sections = new FinalizeSections("g", "- a", "");
            var doc = MarkdownPostProcessor.assembleFinalMarkdown(
                "t", sections, rules, List.of(), List.of());
            assertThat(doc).contains("_Also applicable (2 more):");
            assertThat(doc).contains("rule-11").contains("rule-12");
        }

        @Test
        void sortsConstraintsBySeverity() {
            var rules = List.of(
                ruleWithDesc("low", "should", "Should-severity directive."),
                ruleWithDesc("high", "must", "Must-severity directive."));
            var sections = new FinalizeSections("g", "- a", "");
            var doc = MarkdownPostProcessor.assembleFinalMarkdown(
                "t", sections, rules, List.of(), List.of());
            int mustIdx = doc.indexOf("Must-severity directive");
            int shouldIdx = doc.indexOf("Should-severity directive");
            assertThat(mustIdx).isPositive().isLessThan(shouldIdx);
        }

        @Test
        void rendersStandardsConsultedWhenSkillsOrChecklistsPresent() {
            var sections = new FinalizeSections("g", "- a", "");
            var skill = new Skill("add-endpoint", "Add an Endpoint", null, List.of(), List.of(), null, Scope.empty());
            var doc = MarkdownPostProcessor.assembleFinalMarkdown(
                "t", sections, List.of(), List.of(skill), List.of());
            assertThat(doc).contains("## Standards consulted").contains("skill:add-endpoint");
        }
    }

    @Nested
    class AssembleFinalMarkdownNullSafety {

        @Test
        void nullUserTaskDoesNotCrash() {
            var sections = new FinalizeSections("My goal.", "- accept", "");
            var doc = MarkdownPostProcessor.assembleFinalMarkdown(
                null, sections, List.of(), List.of(), List.of());
            assertThat(doc).contains("## Goal").contains("My goal.");
        }

        @Test
        void nullUserTaskAndEmptyGoalProducesSentinel() {
            var sections = new FinalizeSections("", "- accept", "");
            var doc = MarkdownPostProcessor.assembleFinalMarkdown(
                null, sections, List.of(), List.of(), List.of());
            assertThat(doc).contains("(no goal provided)");
        }
    }

    private static Rule rule(String id, String title) {
        return new Rule(id, title, "must", "A description.", "A rationale.", Scope.empty(), null, null);
    }

    private static Rule mustRule(String id, String title) {
        return rule(id, title);
    }

    private static Rule ruleWithDesc(String id, String severity, String description) {
        return new Rule(id, "Title-" + id, severity, description, null, Scope.empty(), null, null);
    }
}
