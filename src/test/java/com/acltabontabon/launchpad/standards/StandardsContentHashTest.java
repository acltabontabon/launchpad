package com.acltabontabon.launchpad.standards;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.index.StandardsSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the canonical content-hash contract. The hash fingerprints the projected
 * record (id + content + source), is deterministic, never depends on a timestamp,
 * and - critically - is the same hash the sidecar and the audit pass both stamp.
 */
class StandardsContentHashTest {

    private static final StandardsSource SOURCE =
        new StandardsSource("acme-pack", "1.2.0", StandardsSource.ORIGIN_LOCAL_OVERRIDE);

    private static Rule auditableRule() {
        return new Rule(
            "java.no-field-injection", "No field injection", "HIGH",
            "Use constructor injection.", "Field injection hides dependencies.",
            new Scope(List.of("java"), List.of("spring-boot"), List.of(), List.of(), List.of()),
            10,
            new Check("forbid-pattern", "@Autowired", List.of(), List.of(), List.of(), List.of(), null, null));
    }

    @Test
    void ruleHashIsPinnedSoItDoesNotSilentlyChange() {
        // Golden value: if this fails, the rule payload moved and every existing
        // rule hash (and the drift-join with audit findings) just changed.
        assertThat(StandardsContentHash.hashRule(auditableRule(), SOURCE))
            .isEqualTo("9f095bde12dbc885be181544fd1a75c9258e7a617a265225f1b0cbf0e5e9ee66");
    }

    @Test
    void rulesAreDeterministic() {
        assertThat(StandardsContentHash.hashRule(auditableRule(), SOURCE))
            .isEqualTo(StandardsContentHash.hashRule(auditableRule(), SOURCE));
    }

    @Test
    void differentIdYieldsDifferentHashEvenWithSameProse() {
        var a = new Rule("rule-a", "Same title", "HIGH", "Same text.", "Same why.",
            Scope.empty(), 1, null);
        var b = new Rule("rule-b", "Same title", "HIGH", "Same text.", "Same why.",
            Scope.empty(), 1, null);
        assertThat(StandardsContentHash.hashRule(a, SOURCE))
            .isNotEqualTo(StandardsContentHash.hashRule(b, SOURCE));
    }

    @Test
    void skillHashIsDeterministicAndOrderSensitive() {
        var s1 = new Skill("s", "S", "trigger", List.of("one", "two"), List.of("done"), "n", Scope.empty());
        var s2 = new Skill("s", "S", "trigger", List.of("one", "two"), List.of("done"), "n", Scope.empty());
        var reordered = new Skill("s", "S", "trigger", List.of("two", "one"), List.of("done"), "n", Scope.empty());

        assertThat(StandardsContentHash.hashSkill(s1, SOURCE))
            .isEqualTo(StandardsContentHash.hashSkill(s2, SOURCE));
        // Steps are ordered, so reordering them is a real content change.
        assertThat(StandardsContentHash.hashSkill(s1, SOURCE))
            .isNotEqualTo(StandardsContentHash.hashSkill(reordered, SOURCE));
    }

    @Test
    void checklistHashCoversItemsAndIsDeterministic() {
        var items = List.of(new ChecklistItem("a", "first", true), new ChecklistItem("b", "second", false));
        var c1 = new Checklist("c", "C", items, Scope.empty());
        var c2 = new Checklist("c", "C", items, Scope.empty());
        var changed = new Checklist("c", "C",
            List.of(new ChecklistItem("a", "first", true), new ChecklistItem("b", "second CHANGED", false)),
            Scope.empty());

        assertThat(StandardsContentHash.hashChecklist(c1, SOURCE))
            .isEqualTo(StandardsContentHash.hashChecklist(c2, SOURCE));
        assertThat(StandardsContentHash.hashChecklist(c1, SOURCE))
            .isNotEqualTo(StandardsContentHash.hashChecklist(changed, SOURCE));
    }

    @Test
    void nullSourceIsHandled() {
        assertThat(StandardsContentHash.hashRule(auditableRule(), null)).isNotBlank();
    }
}
