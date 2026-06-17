package com.acltabontabon.launchpad.template.rendering;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Locks the chunk-friendly rendering convention for the standards companions:
 * every rule, skill, and checklist is its own H2 with a stable explicit
 * {@code {#slug}} anchor derived from the record id, severity lives below the
 * heading (not in it), and no companion collapses into one prose monolith.
 */
class StandardsRenderingTest {

    private static final Pattern H2 = Pattern.compile("(?m)^## .*$");
    private static final Pattern ANCHOR = Pattern.compile("\\{#([a-z0-9-]+)\\}");

    private static final Rule RULE_A = new Rule(
        "java.no-field-injection", "Constructor Injection", "must",
        "Accept dependencies through constructors as final fields.",
        "Field injection hides dependencies.", null, null, null);
    private static final Rule RULE_B = new Rule(
        "no-util-dumping", "Avoid Static Utility Dumping Grounds", "avoid",
        "Don't accumulate unrelated static methods in Utils classes.",
        "Static methods with no owner signal a missing domain object.", null, null, null);

    private static final Skill SKILL_A = new Skill(
        "add-endpoint", "Add an HTTP Endpoint", "When adding a new HTTP route.",
        List.of("Define request/response records.", "Add a controller method."),
        List.of("A single PR with clear scope."), "Keep new types package-private.", null);
    private static final Skill SKILL_B = new Skill(
        "add-migration", "Add a Database Migration", "When changing the schema.",
        List.of("Write the migration script."), List.of("Migration is reversible."), null, null);

    private static final Checklist CHECK_A = new Checklist(
        "pr-checklist", "Pull Request Checklist",
        List.of(new ChecklistItem("scope", "Diff matches stated scope.", true)), null);
    private static final Checklist CHECK_B = new Checklist(
        "release-checklist", "Release Checklist",
        List.of(new ChecklistItem("changelog", "CHANGELOG updated.", true)), null);

    @Test
    void everyRuleIsItsOwnHeadingWithAUniqueAnchor() {
        var md = StandardsRendering.buildEngineeringRulesMd(List.of(RULE_A, RULE_B));
        assertOneHeadingPerItemWithUniqueAnchors(md, 2);
    }

    @Test
    void everySkillIsItsOwnHeadingWithAUniqueAnchor() {
        var md = StandardsRendering.buildSkillsMd(List.of(SKILL_A, SKILL_B));
        assertOneHeadingPerItemWithUniqueAnchors(md, 2);
    }

    @Test
    void everyChecklistIsItsOwnHeadingWithAUniqueAnchor() {
        var md = StandardsRendering.buildChecklistsMd(List.of(CHECK_A, CHECK_B));
        assertOneHeadingPerItemWithUniqueAnchors(md, 2);
    }

    @Test
    void anchorSlugComesFromStableIdNotTitle() {
        var md = StandardsRendering.buildEngineeringRulesMd(List.of(RULE_A));
        // Dotted/namespaced id slugifies to a hyphenated anchor.
        assertThat(md).contains("{#java-no-field-injection}");

        // Renaming only the title keeps the anchor identical (id is the stability boundary).
        var renamed = new Rule(RULE_A.id(), "Totally Different Title", RULE_A.severity(),
            RULE_A.description(), RULE_A.rationale(), null, null, null);
        var renamedMd = StandardsRendering.buildEngineeringRulesMd(List.of(renamed));
        assertThat(renamedMd).contains("{#java-no-field-injection}");
        assertThat(renamedMd).contains("## Totally Different Title {#java-no-field-injection}");
    }

    @Test
    void ruleSeverityIsNotInTheHeadingLine() {
        var md = StandardsRendering.buildEngineeringRulesMd(List.of(RULE_A));
        var heading = firstH2(md);
        assertThat(heading).isEqualTo("## Constructor Injection {#java-no-field-injection}");
        assertThat(heading).doesNotContain("must");
        assertThat(heading).doesNotContain("·");
    }

    @Test
    void ruleSeverityRendersAsABadgeRightUnderTheHeading() {
        var md = StandardsRendering.buildEngineeringRulesMd(List.of(RULE_A));
        // The exact chunk-friendly convention: heading, blank line, `[must]` badge.
        assertThat(md).contains("## Constructor Injection {#java-no-field-injection}\n\n`[must]`\n\n");
    }

    @Test
    void skillBodyEmitsExpectedSubsectionsOnlyWhenContentExists() {
        var withNotes = StandardsRendering.buildSkillsMd(List.of(SKILL_A));
        assertThat(withNotes).contains("## Add an HTTP Endpoint {#add-endpoint}");
        assertThat(withNotes).contains("### Trigger");
        assertThat(withNotes).contains("### Steps");
        assertThat(withNotes).contains("### Expected output");
        assertThat(withNotes).contains("### Notes");

        // SKILL_B has no notes - the Notes subsection must be omitted.
        var noNotes = StandardsRendering.buildSkillsMd(List.of(SKILL_B));
        assertThat(noNotes).contains("## Add a Database Migration {#add-migration}");
        assertThat(noNotes).doesNotContain("### Notes");
    }

    @Test
    void companionsKeepAHealthyHeadingDensitySoTheyNeverBecomeMonoliths() {
        // Intentionally conservative: this is a smoke test that guards against a
        // regression to one giant prose block, not a proof of ideal RAG chunking.
        double minRatio = 0.12;
        assertThat(headingRatio(StandardsRendering.buildEngineeringRulesMd(List.of(RULE_A, RULE_B))))
            .isGreaterThanOrEqualTo(minRatio);
        assertThat(headingRatio(StandardsRendering.buildSkillsMd(List.of(SKILL_A, SKILL_B))))
            .isGreaterThanOrEqualTo(minRatio);
        assertThat(headingRatio(StandardsRendering.buildChecklistsMd(List.of(CHECK_A, CHECK_B))))
            .isGreaterThanOrEqualTo(minRatio);
    }

    // --- helpers ---

    private static void assertOneHeadingPerItemWithUniqueAnchors(String md, int expectedItems) {
        var headings = new ArrayList<String>();
        Matcher m = H2.matcher(md);
        while (m.find()) headings.add(m.group());
        assertThat(headings).hasSize(expectedItems);

        var anchors = new ArrayList<String>();
        for (var h : headings) {
            Matcher a = ANCHOR.matcher(h);
            assertThat(a.find()).as("heading carries a {#slug} anchor: %s", h).isTrue();
            anchors.add(a.group(1));
        }
        assertThat(anchors).doesNotHaveDuplicates();
    }

    private static String firstH2(String md) {
        Matcher m = H2.matcher(md);
        assertThat(m.find()).isTrue();
        return m.group();
    }

    private static double headingRatio(String md) {
        long nonBlank = md.lines().filter(l -> !l.isBlank()).count();
        long headings = md.lines().filter(l -> l.startsWith("#")).count();
        return nonBlank == 0 ? 0 : (double) headings / nonBlank;
    }
}
