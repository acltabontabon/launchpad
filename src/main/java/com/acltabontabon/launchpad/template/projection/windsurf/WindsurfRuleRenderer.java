package com.acltabontabon.launchpad.template.projection.windsurf;

import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.template.rendering.StandardsRendering;
import java.util.List;

/**
 * Renders canonical records into Windsurf's per-rule markdown format
 * ({@code .windsurf/rules/*.md}).
 *
 * <p>Windsurf rule frontmatter supports a {@code trigger} field:
 * <ul>
 *   <li>{@code always_on} - applied to every prompt</li>
 *   <li>{@code model_decision} - applied when the model judges the rule relevant
 *       (paired with a {@code description})</li>
 *   <li>{@code glob} - applied when matching files are in context</li>
 * </ul>
 * Engineering rules and checklists use {@code always_on}; per-skill files use
 * {@code model_decision} with the canonical trigger as the description.
 */
final class WindsurfRuleRenderer {

    private WindsurfRuleRenderer() {}

    static String renderEngineering(List<Rule> rules) {
        var sb = new StringBuilder();
        sb.append("---\n")
          .append("trigger: always_on\n")
          .append("description: Engineering rules for this project\n")
          .append("---\n\n");
        if (rules == null || rules.isEmpty()) {
            sb.append("_No engineering rules configured._\n");
            return sb.toString();
        }
        for (var rule : rules) {
            sb.append("- **").append(rule.title()).append("**");
            if (rule.severity() != null && !rule.severity().isBlank()) {
                sb.append(" (").append(rule.severity()).append(")");
            }
            sb.append(": ");
            if (rule.description() != null) {
                sb.append(rule.description().replace('\n', ' ').strip());
            }
            sb.append("\n");
            if (rule.rationale() != null && !rule.rationale().isBlank()) {
                sb.append("  _Why:_ ").append(rule.rationale().replace('\n', ' ').strip()).append("\n");
            }
        }
        return sb.toString();
    }

    static String renderSkill(Skill skill) {
        var sb = new StringBuilder();
        sb.append("---\n")
          .append("trigger: model_decision\n");
        if (skill.trigger() != null && !skill.trigger().isBlank()) {
            sb.append("description: ").append(skill.trigger().replace('\n', ' ').strip()).append("\n");
        } else {
            sb.append("description: ").append(StandardsRendering.skillTitle(skill)).append("\n");
        }
        sb.append("---\n\n");
        sb.append("# ").append(StandardsRendering.skillTitle(skill)).append("\n\n");
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

    static String renderChecklists(List<Checklist> checklists) {
        var sb = new StringBuilder();
        sb.append("---\n")
          .append("trigger: always_on\n")
          .append("description: Verification checklists for this project\n")
          .append("---\n\n");
        sb.append("Items marked with `*` are required.\n\n");
        for (var c : checklists) {
            sb.append("## ").append(c.title() != null ? c.title()
                : StandardsRendering.titleFromId(c.id())).append("\n\n");
            if (c.items() != null) {
                for (ChecklistItem item : c.items()) {
                    sb.append("- [ ] ").append(item.text());
                    if (item.required()) sb.append("  *");
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
