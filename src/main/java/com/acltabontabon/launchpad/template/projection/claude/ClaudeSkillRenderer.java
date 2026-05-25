package com.acltabontabon.launchpad.template.projection.claude;

import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.template.rendering.StandardsRendering;

/**
 * Renders a canonical {@link Skill} into the Markdown body expected by
 * Claude Code at {@code .claude/skills/<id>/SKILL.md}.
 *
 * <p>The class is intentionally scoped to the Claude projection package -
 * canonical renderers must not know about this layout. Title casing is
 * delegated to {@link StandardsRendering#skillTitle(Skill)} because it
 * operates on the canonical record, not on a Claude-specific shape.
 */
final class ClaudeSkillRenderer {

    private ClaudeSkillRenderer() {}

    static String render(Skill skill) {
        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skill.id()).append("\n");
        if (skill.trigger() != null && !skill.trigger().isBlank()) {
            sb.append("description: ").append(skill.trigger()).append("\n");
        }
        sb.append("---\n\n");
        sb.append("# ").append(StandardsRendering.skillTitle(skill)).append("\n\n");
        if (skill.trigger() != null && !skill.trigger().isBlank()) {
            sb.append("**Trigger:** ").append(skill.trigger()).append("\n\n");
        }
        if (skill.steps() != null && !skill.steps().isEmpty()) {
            sb.append("## Steps\n\n");
            int i = 1;
            for (var step : skill.steps()) {
                sb.append(i++).append(". ").append(step).append("\n");
            }
            sb.append("\n");
        }
        if (skill.outputExpectations() != null && !skill.outputExpectations().isEmpty()) {
            sb.append("## Expected Output\n\n");
            skill.outputExpectations().forEach(o -> sb.append("- ").append(o).append("\n"));
            sb.append("\n");
        }
        if (skill.notes() != null && !skill.notes().isBlank()) {
            sb.append("## Notes\n\n").append(skill.notes()).append("\n");
        }
        return sb.toString();
    }
}
