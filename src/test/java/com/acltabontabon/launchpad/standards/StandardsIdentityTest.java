package com.acltabontabon.launchpad.standards;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the stable-id guarantee: every record ends up with a non-blank, unique id.
 * Authored ids are kept verbatim; blank ids are filled from a slug of the title
 * (or item text) with a deterministic numeric suffix on collision.
 */
class StandardsIdentityTest {

    private static Rule rule(String id, String title) {
        return new Rule(id, title, "HIGH", "text", "why", Scope.empty(), 1, null);
    }

    private static Skill skill(String id, String title) {
        return new Skill(id, title, null, null, null, null, Scope.empty());
    }

    private static Checklist checklist(String id, String title, List<ChecklistItem> items) {
        return new Checklist(id, title, items, Scope.empty());
    }

    @Test
    void authoredRuleIdsAreKeptVerbatim() {
        var out = StandardsIdentity.normalizeRules(List.of(rule("java.no-autowired", "X")));
        assertThat(out).extracting(Rule::id).containsExactly("java.no-autowired");
    }

    @Test
    void blankRuleIdFallsBackToSlugOfTitle() {
        var out = StandardsIdentity.normalizeRules(List.of(rule("", "Use explicit timeouts")));
        assertThat(out).extracting(Rule::id).containsExactly("use-explicit-timeouts");
    }

    @Test
    void collidingBlankRuleIdsGetDeterministicSuffixesInAuthoredOrder() {
        var out = StandardsIdentity.normalizeRules(List.of(
            rule(null, "Use explicit timeouts"),
            rule(null, "Use explicit timeouts"),
            rule(null, "Use explicit timeouts")));
        assertThat(out).extracting(Rule::id)
            .containsExactly("use-explicit-timeouts", "use-explicit-timeouts-2", "use-explicit-timeouts-3");
    }

    @Test
    void generatedIdSkipsPastAnAuthoredCollision() {
        var out = StandardsIdentity.normalizeRules(List.of(
            rule("use-explicit-timeouts", "Authored"),
            rule(null, "Use explicit timeouts")));
        assertThat(out).extracting(Rule::id)
            .containsExactly("use-explicit-timeouts", "use-explicit-timeouts-2");
    }

    @Test
    void collidingBlankSkillIdsGetSuffixes() {
        var out = StandardsIdentity.normalizeSkills(List.of(skill("", "Add endpoint"), skill("", "Add endpoint")));
        assertThat(out).extracting(Skill::id).containsExactly("add-endpoint", "add-endpoint-2");
    }

    @Test
    void collidingBlankChecklistIdsGetSuffixes() {
        var out = StandardsIdentity.normalizeChecklists(List.of(
            checklist("", "Release", List.of()), checklist("", "Release", List.of())));
        assertThat(out).extracting(Checklist::id).containsExactly("release", "release-2");
    }

    @Test
    void checklistItemIdsAreUniquePerChecklist() {
        var c = checklist("c", "C", List.of(
            new ChecklistItem("", "Run the tests", true),
            new ChecklistItem("", "Run the tests", false)));
        var out = StandardsIdentity.normalizeChecklists(List.of(c));
        assertThat(out.get(0).items()).extracting(ChecklistItem::id)
            .containsExactly("run-the-tests", "run-the-tests-2");
    }

    @Test
    void repeatedNormalizationIsStable() {
        var input = List.of(rule(null, "Use explicit timeouts"), rule(null, "Use explicit timeouts"));
        assertThat(StandardsIdentity.normalizeRules(input))
            .isEqualTo(StandardsIdentity.normalizeRules(input));
    }

    @Test
    void emptyTitleFallsBackToKindBase() {
        var out = StandardsIdentity.normalizeRules(List.of(rule("", "  "), rule("", null)));
        assertThat(out).extracting(Rule::id).containsExactly("rule", "rule-2");
    }
}
