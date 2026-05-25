package com.acltabontabon.launchpad.template.projection.claude;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.template.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeSkillsProjectionTest {

    private static final Skill SAMPLE_SKILL = new Skill(
        "add-feature", "Add a Feature", "When adding a new user-facing capability.",
        List.of("Identify the entry point.", "Wire through existing layers."),
        List.of("A single PR with clear scope."), "Keep new types package-private.", null);

    private final ClaudeSkillsProjection projection = new ClaudeSkillsProjection();

    @Test
    void idIsClaude() {
        assertThat(projection.id()).isEqualTo("claude");
    }

    @Test
    void emptySkillsListReturnsEmptyOutput() {
        var files = projection.project(null, List.of(), List.of(), List.of());
        assertThat(files).isEmpty();
    }

    @Test
    void emitsOneSkillFilePerSkillAtClaudePath() {
        var files = projection.project(null, List.of(), List.of(SAMPLE_SKILL), List.of());

        assertThat(files).hasSize(1);
        var f = files.get(0);
        assertThat(f.relativePath()).isEqualTo(".claude/skills/add-feature/SKILL.md");
        assertThat(f.kind()).isEqualTo(GeneratedFile.FileKind.SKILL);
        assertThat(f.content()).startsWith("---\nname: add-feature\n");
        assertThat(f.content()).contains("description: When adding a new user-facing capability.");
        assertThat(f.content()).contains("# Add a Feature");
        assertThat(f.content()).contains("**Trigger:** When adding a new user-facing capability.");
        assertThat(f.content()).contains("## Steps");
        assertThat(f.content()).contains("1. Identify the entry point.");
        assertThat(f.content()).contains("## Expected Output");
        assertThat(f.content()).contains("- A single PR with clear scope.");
        assertThat(f.content()).contains("## Notes");
        assertThat(f.content()).contains("Keep new types package-private.");
    }

    @Test
    void emitsOneFilePerSkillForMultipleSkills() {
        var skillB = new Skill("fix-bug", "Fix a Bug", "When investigating a defect.",
            List.of("Reproduce.", "Patch."), List.of("Regression test."), null, null);

        var files = projection.project(null, List.of(), List.of(SAMPLE_SKILL, skillB), List.of());

        assertThat(files).hasSize(2);
        assertThat(files.get(0).relativePath()).isEqualTo(".claude/skills/add-feature/SKILL.md");
        assertThat(files.get(1).relativePath()).isEqualTo(".claude/skills/fix-bug/SKILL.md");
    }
}
