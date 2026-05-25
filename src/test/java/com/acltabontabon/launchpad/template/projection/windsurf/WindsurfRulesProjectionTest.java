package com.acltabontabon.launchpad.template.projection.windsurf;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.template.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindsurfRulesProjectionTest {

    private static final Rule SAMPLE_RULE = new Rule(
        "clean-code", "Clean Code", "must",
        "Write self-documenting code.", "Code is read more often than written.",
        null, null, null);

    private static final Skill SAMPLE_SKILL = new Skill(
        "add-feature", "Add a Feature", "When adding a new user-facing capability.",
        List.of("Identify the entry point.", "Wire through existing layers."),
        List.of("A single PR with clear scope."), "Keep new types package-private.", null);

    private static final Checklist SAMPLE_CHECKLIST = new Checklist(
        "pr-checklist", "Pull Request Checklist",
        List.of(new ChecklistItem("scope", "Diff matches stated scope.", true)),
        null);

    private final WindsurfRulesProjection projection = new WindsurfRulesProjection();

    @Test
    void idAndDisplayName() {
        assertThat(projection.id()).isEqualTo("windsurf");
        assertThat(projection.displayName()).isEqualTo("Windsurf");
        assertThat(projection.description()).contains(".windsurf/rules");
    }

    @Test
    void emptyInputReturnsNothing() {
        assertThat(projection.project(null, List.of(), List.of(), List.of())).isEmpty();
    }

    @Test
    void emitsEngineeringRulesBundleWithAlwaysOn() {
        var files = projection.project(null, List.of(SAMPLE_RULE), List.of(), List.of());

        assertThat(files).hasSize(1);
        var f = files.get(0);
        assertThat(f.relativePath()).isEqualTo(".windsurf/rules/engineering.md");
        assertThat(f.kind()).isEqualTo(GeneratedFile.FileKind.RULES);
        assertThat(f.content()).startsWith("---\n");
        assertThat(f.content()).contains("trigger: always_on");
        assertThat(f.content()).contains("**Clean Code**");
        assertThat(f.content()).contains("Write self-documenting code.");
        assertThat(f.content()).contains("_Why:_ Code is read more often than written.");
    }

    @Test
    void emitsOnePerSkillWithModelDecisionTrigger() {
        var files = projection.project(null, List.of(), List.of(SAMPLE_SKILL), List.of());

        assertThat(files).hasSize(1);
        var f = files.get(0);
        assertThat(f.relativePath()).isEqualTo(".windsurf/rules/add-feature.md");
        assertThat(f.kind()).isEqualTo(GeneratedFile.FileKind.SKILL);
        assertThat(f.content()).contains("trigger: model_decision");
        assertThat(f.content()).contains("description: When adding a new user-facing capability.");
        assertThat(f.content()).contains("# Add a Feature");
        assertThat(f.content()).contains("1. Identify the entry point.");
    }

    @Test
    void emitsChecklistsBundleWhenPresent() {
        var files = projection.project(null, List.of(), List.of(), List.of(SAMPLE_CHECKLIST));

        assertThat(files).hasSize(1);
        var f = files.get(0);
        assertThat(f.relativePath()).isEqualTo(".windsurf/rules/checklists.md");
        assertThat(f.content()).contains("trigger: always_on");
        assertThat(f.content()).contains("## Pull Request Checklist");
        assertThat(f.content()).contains("- [ ] Diff matches stated scope.  *");
    }

    @Test
    void fullProjectionContainsAllThreeFileKinds() {
        var files = projection.project(null,
            List.of(SAMPLE_RULE),
            List.of(SAMPLE_SKILL),
            List.of(SAMPLE_CHECKLIST));

        assertThat(files).extracting(GeneratedFile::relativePath)
            .containsExactly(
                ".windsurf/rules/engineering.md",
                ".windsurf/rules/add-feature.md",
                ".windsurf/rules/checklists.md"
            );
    }
}
