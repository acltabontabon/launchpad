package com.acltabontabon.launchpad.template.rendering;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import java.util.List;

public final class StandardsRendering {

    public static final String RULES_PLACEHOLDER =
        "_No engineering rules configured. Set `launchpad.standards.remote.url` in Launchpad settings, "
        + "or add `.launchpad/standards/rules.yml` to this project._\n";

    public static final String SKILLS_PLACEHOLDER =
        "_No workflow skills configured. Set `launchpad.standards.remote.url` in Launchpad settings, "
        + "or add `.launchpad/standards/skills.yml` to this project._\n";

    private StandardsRendering() {}

    public static String skillTitle(Skill skill) {
        if (skill.title() != null && !skill.title().isBlank()) return skill.title();
        return titleFromId(skill.id());
    }

    public static String titleFromId(String id) {
        var parts = id.split("-");
        var sb = new StringBuilder();
        for (var p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    public static String buildClaudeSkillFile(Skill skill) {
        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skill.id()).append("\n");
        if (skill.trigger() != null && !skill.trigger().isBlank()) {
            sb.append("description: ").append(skill.trigger()).append("\n");
        }
        sb.append("---\n\n");
        sb.append("# ").append(skillTitle(skill)).append("\n\n");
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

    public static String buildAiIndex(ProjectContext ctx, List<Skill> skills, boolean hasChecklists) {
        var sb = new StringBuilder();
        sb.append("# Project Index - ").append(ctx.name()).append("\n\n");
        sb.append("| File | Purpose |\n");
        sb.append("|------|---------|\n");
        sb.append("| `AGENTS.md` | Main context file - start here |\n");
        sb.append("| `.ai/engineering-rules.md` | Engineering rules for this project |\n");
        sb.append("| `.ai/stack.md` | Stack details and dependency notes |\n");
        if (hasChecklists) sb.append("| `.ai/checklists.md` | Verification checklists |\n");
        sb.append("| `.claude/skills/` | Curated workflow skills (invocable via `/<skill-id>`) |\n");
        if (!skills.isEmpty()) {
            sb.append("\n## Available Skills\n\n");
            skills.forEach(s -> sb.append("- `/").append(s.id()).append("` - ").append(
                s.trigger() == null ? "" : s.trigger()
            ).append("\n"));
        }
        return sb.toString();
    }

    public static String buildStackMd(ProjectContext ctx) {
        var sb = new StringBuilder();
        sb.append("# Stack - ").append(ctx.name()).append("\n\n");
        sb.append("**Detected:** ").append(ctx.stack().displayName()).append("\n\n");
        if (ctx.stack().framework() != null) {
            sb.append("**Framework:** ").append(ctx.stack().framework()).append("\n\n");
        }
        sb.append("**Language:** ").append(ctx.stack().language()).append("\n");
        if (ctx.stack().buildTool() != null) {
            sb.append("**Build tool:** ").append(ctx.stack().buildTool()).append("\n");
        }
        sb.append("\n");

        if (!ctx.dependencies().isEmpty()) {
            sb.append("## Dependencies\n\n");
            ctx.dependencies().forEach(d -> sb.append("- ").append(d.display()).append("\n"));
        }

        if (!ctx.entryPoints().isEmpty()) {
            sb.append("\n## Entry Points\n\n");
            ctx.entryPoints().forEach((k, v) -> sb.append("- **").append(k).append(":** `").append(v).append("`\n"));
        }
        return sb.toString();
    }

    public static String buildEngineeringRulesMd(List<Rule> rules) {
        var sb = new StringBuilder();
        sb.append("# Engineering Rules\n\n");
        sb.append("These rules apply to all work in this project, regardless of feature or task.\n\n");
        if (rules.isEmpty()) {
            sb.append(RULES_PLACEHOLDER);
            return sb.toString();
        }
        rules.forEach(rule -> {
            sb.append("## ").append(rule.title());
            if (rule.severity() != null && !rule.severity().isBlank()) {
                sb.append("  ·  ").append(rule.severity());
            }
            sb.append("\n\n");
            if (rule.description() != null && !rule.description().isBlank()) {
                sb.append(rule.description().strip()).append("\n\n");
            }
            if (rule.rationale() != null && !rule.rationale().isBlank()) {
                sb.append("_Why:_ ").append(rule.rationale().strip()).append("\n\n");
            }
        });
        return sb.toString();
    }

    public static String buildChecklistsMd(List<Checklist> checklists) {
        var sb = new StringBuilder();
        sb.append("# Checklists\n\n");
        sb.append("Verification gates before declaring work done. Items marked with `*` are required.\n\n");
        checklists.forEach(c -> {
            sb.append("## ").append(c.title() != null ? c.title() : titleFromId(c.id())).append("\n\n");
            if (c.items() != null) {
                for (ChecklistItem item : c.items()) {
                    sb.append("- [ ] ").append(item.text());
                    if (item.required()) sb.append("  *");
                    sb.append("\n");
                }
            }
            sb.append("\n");
        });
        return sb.toString();
    }

}
