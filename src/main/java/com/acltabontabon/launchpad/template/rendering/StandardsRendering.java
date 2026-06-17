package com.acltabontabon.launchpad.template.rendering;

import com.acltabontabon.launchpad.model.ModelIdentity;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import java.util.List;

public final class StandardsRendering {

    public static final String RULES_PLACEHOLDER =
        "_No engineering rules configured. Set `launchpad.standards.remote.url` in Launchpad settings, "
        + "or add a standards pack under `.launchpad/standards/` with a `standards-pack.yml` manifest._\n";

    public static final String SKILLS_PLACEHOLDER =
        "_No workflow skills configured. Set `launchpad.standards.remote.url` in Launchpad settings, "
        + "or add a standards pack under `.launchpad/standards/` with a `standards-pack.yml` manifest._\n";

    private static final String SKILLS_FILE = ".ai/skills.md";

    private StandardsRendering() {}

    /**
     * Renders a Markdown heading carrying a stable explicit anchor slug, e.g.
     * {@code "## Constructor Injection {#java-no-field-injection}"}. The anchor
     * value comes only from {@link ModelIdentity#slug} of the record's stable
     * {@code id} - never from the display title - so the slug survives title
     * edits and stays unique. Uniqueness is guaranteed upstream by
     * {@code StandardsIdentity}; this renderer never invents fallback slugs or
     * appends {@code -2}-style dedup suffixes.
     */
    public static String headingWithSlug(String level, String displayTitle, String id) {
        return level + " " + displayTitle + " {#" + ModelIdentity.slug(id) + "}\n\n";
    }

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

    public static String buildAiIndex(ProjectContext ctx, List<Skill> skills, boolean hasChecklists) {
        var sb = new StringBuilder();
        sb.append("# Project Index - ").append(ctx.name()).append("\n\n");
        sb.append("| File | Purpose |\n");
        sb.append("|------|---------|\n");
        sb.append("| `AGENTS.md` | Main context file - start here |\n");
        sb.append("| `.ai/engineering-rules.md` | Engineering rules for this project |\n");
        if (!skills.isEmpty()) sb.append("| `").append(SKILLS_FILE).append("` | Workflow skills for this project |\n");
        sb.append("| `.ai/stack.md` | Stack details and dependency notes |\n");
        if (hasChecklists) sb.append("| `.ai/checklists.md` | Verification checklists |\n");
        if (!skills.isEmpty()) {
            // Navigation only - one pointer per skill (id + trigger). The full
            // per-skill prose lives in `.ai/skills.md`, never inlined here.
            sb.append("\n## Available Skills\n\n");
            skills.forEach(s -> sb.append("- `").append(s.id()).append("` - ").append(
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
            // Heading stays clean for BM25/chunk titles; the anchor is stable.
            sb.append(headingWithSlug("##", rule.title(), rule.id()));
            // Severity stays searchable and visible as a body badge, not in the heading.
            if (rule.severity() != null && !rule.severity().isBlank()) {
                sb.append("`[").append(rule.severity()).append("]`\n\n");
            }
            if (rule.description() != null && !rule.description().isBlank()) {
                sb.append(rule.description().strip()).append("\n\n");
            }
            if (rule.rationale() != null && !rule.rationale().isBlank()) {
                sb.append("_Why:_ ").append(rule.rationale().strip()).append("\n\n");
            }
        });
        return sb.toString();
    }

    public static String buildSkillsMd(List<Skill> skills) {
        var sb = new StringBuilder();
        sb.append("# Skills\n\n");
        sb.append("Task-scoped workflows. Each skill is its own section, anchored by a stable id.\n\n");
        if (skills.isEmpty()) {
            sb.append(SKILLS_PLACEHOLDER);
            return sb.toString();
        }
        skills.forEach(skill -> {
            sb.append(headingWithSlug("##", skillTitle(skill), skill.id()));
            if (skill.trigger() != null && !skill.trigger().isBlank()) {
                sb.append("### Trigger\n\n").append(skill.trigger().strip()).append("\n\n");
            }
            if (skill.steps() != null && !skill.steps().isEmpty()) {
                sb.append("### Steps\n\n");
                int i = 1;
                for (var step : skill.steps()) {
                    sb.append(i++).append(". ").append(step).append("\n");
                }
                sb.append("\n");
            }
            if (skill.outputExpectations() != null && !skill.outputExpectations().isEmpty()) {
                sb.append("### Expected output\n\n");
                skill.outputExpectations().forEach(o -> sb.append("- ").append(o).append("\n"));
                sb.append("\n");
            }
            if (skill.notes() != null && !skill.notes().isBlank()) {
                sb.append("### Notes\n\n").append(skill.notes().strip()).append("\n\n");
            }
        });
        return sb.toString();
    }

    public static String buildChecklistsMd(List<Checklist> checklists) {
        var sb = new StringBuilder();
        sb.append("# Checklists\n\n");
        sb.append("Verification gates before declaring work done. Items marked with `*` are required.\n\n");
        checklists.forEach(c -> {
            var title = c.title() != null && !c.title().isBlank() ? c.title() : titleFromId(c.id());
            sb.append(headingWithSlug("##", title, c.id()));
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
