package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Adapter;
import com.acltabontabon.launchpad.standards.AdapterOutput;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Prompt;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.standards.StandardsLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Assembles final file content from AI-generated summaries and standards loaded from YAML.
 * <p>
 * Rendering modes:
 *   - Adapter-driven: when the standards source includes an adapter for the current target
 *     (claude / cursor), the adapter's first output drives the primary combined context file's
 *     path, frontmatter, and section list. Secondary files (.ai/engineering-rules.md,
 *     per-skill SKILL.md, etc.) are always emitted at hardcoded paths for tool-specific
 *     discoverability.
 *   - Legacy: when no adapter resolves (flat-format override or unconfigured remote), the
 *     primary file falls back to the previous hardcoded path (CLAUDE.md / .cursorrules) and
 *     content shape.
 */
@Component
public class ContextTemplateEngine {

    private final StandardsLoader standardsLoader;

    public ContextTemplateEngine(StandardsLoader standardsLoader) {
        this.standardsLoader = standardsLoader;
    }

    public List<GeneratedFile> buildFiles(
        ProjectContext ctx,
        ContextTarget target,
        String projectSummary,
        String targetSpecificContent
    ) {
        var projectRoot = Path.of(ctx.rootPath());
        var rules = standardsLoader.loadRules(projectRoot);
        var skills = standardsLoader.loadSkills(projectRoot);
        var checklists = standardsLoader.loadChecklists(projectRoot);
        var prompts = standardsLoader.loadPrompts(projectRoot);
        var adapter = standardsLoader.loadAdapter(projectRoot, adapterIdFor(target));

        return switch (target) {
            case CLAUDE -> buildClaudeFiles(ctx, projectSummary, targetSpecificContent,
                rules, skills, checklists, prompts, adapter);
            case CURSOR -> buildCursorFiles(ctx, projectSummary, targetSpecificContent,
                rules, skills, checklists, prompts, adapter);
        };
    }

    private static String adapterIdFor(ContextTarget target) {
        return switch (target) {
            case CLAUDE -> "claude";
            case CURSOR -> "cursor";
        };
    }

    // === Claude ===

    private List<GeneratedFile> buildClaudeFiles(
        ProjectContext ctx, String summary, String llmSkills,
        List<Rule> rules, List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        Optional<Adapter> adapter
    ) {
        var files = new ArrayList<GeneratedFile>();

        var primaryOutput = adapter.flatMap(ContextTemplateEngine::firstOutput);
        if (primaryOutput.isPresent()) {
            var out = primaryOutput.get();
            files.add(new GeneratedFile(out.path(),
                buildCombinedContext(ctx, summary, llmSkills, rules, skills, checklists, prompts, out, false),
                GeneratedFile.FileKind.CONTEXT));
        } else {
            files.add(new GeneratedFile("CLAUDE.md",
                buildLegacyClaudeMd(ctx, summary, llmSkills),
                GeneratedFile.FileKind.CONTEXT));
        }

        files.add(new GeneratedFile(".ai/index.md", buildAiIndex(ctx, skills, !checklists.isEmpty(), !prompts.isEmpty()),
            GeneratedFile.FileKind.INDEX));
        files.add(new GeneratedFile(".ai/engineering-rules.md", buildEngineeringRulesMd(rules),
            GeneratedFile.FileKind.RULES));
        files.add(new GeneratedFile(".ai/stack.md", buildStackMd(ctx),
            GeneratedFile.FileKind.CONTEXT));
        if (!checklists.isEmpty()) {
            files.add(new GeneratedFile(".ai/checklists.md", buildChecklistsMd(checklists),
                GeneratedFile.FileKind.RULES));
        }
        if (!prompts.isEmpty()) {
            files.add(new GeneratedFile(".ai/prompts.md", buildPromptsMd(prompts),
                GeneratedFile.FileKind.RULES));
        }

        skills.forEach(s -> files.add(new GeneratedFile(
            ".claude/skills/" + s.id() + "/SKILL.md",
            buildClaudeSkillFile(s),
            GeneratedFile.FileKind.SKILL
        )));
        return files;
    }

    // === Cursor ===

    private List<GeneratedFile> buildCursorFiles(
        ProjectContext ctx, String summary, String llmRules,
        List<Rule> rules, List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        Optional<Adapter> adapter
    ) {
        var files = new ArrayList<GeneratedFile>();

        var primaryOutput = adapter.flatMap(ContextTemplateEngine::firstOutput);
        if (primaryOutput.isPresent()) {
            var out = primaryOutput.get();
            files.add(new GeneratedFile(out.path(),
                buildCombinedContext(ctx, summary, llmRules, rules, skills, checklists, prompts, out, true),
                GeneratedFile.FileKind.CONTEXT));
        } else {
            files.add(new GeneratedFile(".cursorrules",
                buildLegacyCursorRules(ctx, summary, llmRules),
                GeneratedFile.FileKind.CONTEXT));
        }

        files.add(new GeneratedFile(".cursor/rules/engineering.mdc", buildCursorEngineeringRules(rules),
            GeneratedFile.FileKind.RULES));
        files.add(new GeneratedFile(".cursor/rules/skills.mdc", buildCursorSkills(skills),
            GeneratedFile.FileKind.RULES));
        files.add(new GeneratedFile(".cursor/rules/stack.mdc", buildCursorStackRules(ctx),
            GeneratedFile.FileKind.CONTEXT));
        if (!checklists.isEmpty()) {
            files.add(new GeneratedFile(".cursor/rules/checklists.mdc", buildCursorChecklists(checklists),
                GeneratedFile.FileKind.RULES));
        }
        if (!prompts.isEmpty()) {
            files.add(new GeneratedFile(".cursor/rules/prompts.mdc", buildCursorPrompts(prompts),
                GeneratedFile.FileKind.RULES));
        }
        return files;
    }

    private static Optional<AdapterOutput> firstOutput(Adapter a) {
        return a.outputs() == null || a.outputs().isEmpty()
            ? Optional.empty()
            : Optional.of(a.outputs().get(0));
    }

    // === Combined context file (adapter-driven) ===

    private String buildCombinedContext(
        ProjectContext ctx, String summary, String llmGenerated,
        List<Rule> rules, List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        AdapterOutput output, boolean withFrontmatter
    ) {
        var sb = new StringBuilder();

        if (withFrontmatter) {
            sb.append("---\n");
            var fm = output.frontmatter() != null ? output.frontmatter() : Map.<String, String>of();
            fm.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
            sb.append("---\n\n");
        }

        sb.append("# ").append(ctx.name()).append("\n\n");
        sb.append("> Generated by Launchpad - standards pack rendered for the target AI tool.\n\n");
        sb.append("## Project Overview\n\n").append(safe(summary, "_(none generated)_")).append("\n\n");
        sb.append("## Stack\n\n").append(ctx.detectedStack()).append("\n\n");

        var includes = output.includes() != null ? output.includes() : List.<String>of();

        if (includes.contains("rules")) {
            sb.append("## Engineering Rules\n\n");
            if (rules.isEmpty()) {
                sb.append(RULES_PLACEHOLDER).append("\n");
            } else {
                rules.forEach(rule -> appendRule(sb, rule));
            }
        }
        if (includes.contains("skills")) {
            sb.append("## Workflow Skills\n\n");
            if (skills.isEmpty()) {
                sb.append(SKILLS_PLACEHOLDER).append("\n");
            } else {
                skills.forEach(skill -> appendSkill(sb, skill));
            }
        }
        if (includes.contains("checklists") && !checklists.isEmpty()) {
            sb.append("## Checklists\n\n");
            checklists.forEach(c -> appendChecklist(sb, c));
        }
        if (includes.contains("prompts") && !prompts.isEmpty()) {
            sb.append("## Reusable Prompts\n\n");
            prompts.forEach(p -> appendPrompt(sb, p));
        }

        if (llmGenerated != null && !llmGenerated.isBlank()) {
            sb.append("## Project-Specific Notes\n\n").append(llmGenerated).append("\n");
        }
        return sb.toString();
    }

    // === Legacy primary files (no adapter) ===

    private String buildLegacyClaudeMd(ProjectContext ctx, String summary, String llmSkills) {
        return """
            # %s

            > Generated by Launchpad - local AI context generator

            ## Project Overview

            %s

            ## Stack

            %s

            ## Key Files

            %s

            ## Skills

            Curated workflow skills for this project live as invocable Claude Code skills under
            `.claude/skills/`. Type `/<skill-id>` to invoke one, or describe a matching task and
            Claude will activate the relevant skill automatically.

            ### Project-Specific Skills

            Generated from this project's context by the local AI model.

            %s

            ## Engineering Rules

            See `.ai/engineering-rules.md` for the full list of rules that apply to this project.
            """.formatted(
                ctx.name(),
                summary,
                ctx.detectedStack(),
                formatFileList(ctx.sourceFiles(), 20),
                llmSkills == null || llmSkills.isBlank() ? "_(none generated)_" : llmSkills
            );
    }

    private String buildLegacyCursorRules(ProjectContext ctx, String summary, String generatedRules) {
        return """
            # Cursor Rules - %s

            ## Project Context

            %s

            ## Stack
            %s

            ## Project-Specific Rules

            %s

            ## Engineering Rules

            See `.cursor/rules/engineering.mdc` for the full engineering rule set,
            and `.cursor/rules/skills.mdc` for curated workflow skills.
            """.formatted(ctx.name(), summary, ctx.detectedStack(), generatedRules);
    }

    // === Skill files (Claude) ===

    private String buildClaudeSkillFile(Skill skill) {
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

    private static String skillTitle(Skill skill) {
        if (skill.title() != null && !skill.title().isBlank()) return skill.title();
        return titleFromId(skill.id());
    }

    private static String titleFromId(String id) {
        var parts = id.split("-");
        var sb = new StringBuilder();
        for (var p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    // === Rule / Skill / Checklist / Prompt inline appenders ===

    private static void appendRule(StringBuilder sb, Rule rule) {
        sb.append("### ").append(rule.title());
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
    }

    private static void appendSkill(StringBuilder sb, Skill skill) {
        sb.append("### ").append(skillTitle(skill)).append("\n\n");
        if (skill.trigger() != null && !skill.trigger().isBlank()) {
            sb.append("**Trigger:** ").append(skill.trigger().strip()).append("\n\n");
        }
        if (skill.steps() != null && !skill.steps().isEmpty()) {
            sb.append("**Steps:**\n");
            int i = 1;
            for (var step : skill.steps()) {
                sb.append(i++).append(". ").append(step).append("\n");
            }
            sb.append("\n");
        }
        if (skill.outputExpectations() != null && !skill.outputExpectations().isEmpty()) {
            sb.append("**Expected Output:**\n");
            skill.outputExpectations().forEach(o -> sb.append("- ").append(o).append("\n"));
            sb.append("\n");
        }
        if (skill.notes() != null && !skill.notes().isBlank()) {
            sb.append("**Notes:** ").append(skill.notes().strip()).append("\n\n");
        }
    }

    private static void appendChecklist(StringBuilder sb, Checklist checklist) {
        sb.append("### ").append(checklist.title() != null ? checklist.title() : titleFromId(checklist.id())).append("\n\n");
        if (checklist.items() != null) {
            for (ChecklistItem item : checklist.items()) {
                sb.append("- [ ] ").append(item.text());
                if (item.required()) sb.append("  *");
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    private static void appendPrompt(StringBuilder sb, Prompt prompt) {
        sb.append("### ").append(prompt.title() != null ? prompt.title() : titleFromId(prompt.id())).append("\n\n");
        if (prompt.template() != null && !prompt.template().isBlank()) {
            sb.append("```\n").append(prompt.template().strip()).append("\n```\n\n");
        }
    }

    // === Secondary files ===

    private String buildAiIndex(ProjectContext ctx, List<Skill> skills, boolean hasChecklists, boolean hasPrompts) {
        var sb = new StringBuilder();
        sb.append("# Project Index - ").append(ctx.name()).append("\n\n");
        sb.append("| File | Purpose |\n");
        sb.append("|------|---------|\n");
        sb.append("| `CLAUDE.md` | Main context file - start here |\n");
        sb.append("| `.ai/engineering-rules.md` | Engineering rules for this project |\n");
        sb.append("| `.ai/stack.md` | Stack details and dependency notes |\n");
        if (hasChecklists) sb.append("| `.ai/checklists.md` | Verification checklists |\n");
        if (hasPrompts) sb.append("| `.ai/prompts.md` | Reusable prompt templates |\n");
        sb.append("| `.claude/skills/` | Curated workflow skills (invocable via `/<skill-id>`) |\n");
        if (!skills.isEmpty()) {
            sb.append("\n## Available Skills\n\n");
            skills.forEach(s -> sb.append("- `/").append(s.id()).append("` - ").append(
                s.trigger() == null ? "" : s.trigger()
            ).append("\n"));
        }
        return sb.toString();
    }

    private String buildStackMd(ProjectContext ctx) {
        var sb = new StringBuilder();
        sb.append("# Stack - ").append(ctx.name()).append("\n\n");
        sb.append("**Detected:** ").append(ctx.detectedStack()).append("\n\n");

        if (!ctx.dependencies().isEmpty()) {
            sb.append("## Dependencies\n\n");
            ctx.dependencies().forEach(d -> sb.append("- ").append(d).append("\n"));
        }

        if (!ctx.entryPoints().isEmpty()) {
            sb.append("\n## Entry Points\n\n");
            ctx.entryPoints().forEach((k, v) -> sb.append("- **").append(k).append(":** `").append(v).append("`\n"));
        }
        return sb.toString();
    }

    private static final String RULES_PLACEHOLDER =
        "_No engineering rules configured. Set `launchpad.standards.remote.url` in Launchpad settings, "
        + "or add `.launchpad/standards/rules.yml` to this project._\n";

    private static final String SKILLS_PLACEHOLDER =
        "_No workflow skills configured. Set `launchpad.standards.remote.url` in Launchpad settings, "
        + "or add `.launchpad/standards/skills.yml` to this project._\n";

    private String buildEngineeringRulesMd(List<Rule> rules) {
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

    private String buildChecklistsMd(List<Checklist> checklists) {
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

    private String buildPromptsMd(List<Prompt> prompts) {
        var sb = new StringBuilder();
        sb.append("# Reusable Prompts\n\n");
        sb.append("Templates for common tasks. Substitute `{{placeholder}}` values before sending.\n\n");
        prompts.forEach(p -> {
            sb.append("## ").append(p.title() != null ? p.title() : titleFromId(p.id())).append("\n\n");
            if (p.template() != null && !p.template().isBlank()) {
                sb.append("```\n").append(p.template().strip()).append("\n```\n\n");
            }
        });
        return sb.toString();
    }

    // === Cursor secondary files ===

    private String buildCursorEngineeringRules(List<Rule> rules) {
        var sb = new StringBuilder();
        sb.append("---\ndescription: Engineering rules for this project\nglobs: **/*\n---\n\n");
        if (rules.isEmpty()) {
            sb.append(RULES_PLACEHOLDER);
            return sb.toString();
        }
        rules.forEach(rule -> {
            sb.append("- **").append(rule.title()).append("**");
            if (rule.severity() != null && !rule.severity().isBlank()) {
                sb.append(" (").append(rule.severity()).append(")");
            }
            sb.append(": ");
            if (rule.description() != null) sb.append(rule.description().replace('\n', ' ').strip());
            sb.append("\n");
            if (rule.rationale() != null && !rule.rationale().isBlank()) {
                sb.append("  _Why:_ ").append(rule.rationale().replace('\n', ' ').strip()).append("\n");
            }
        });
        return sb.toString();
    }

    private String buildCursorSkills(List<Skill> skills) {
        var sb = new StringBuilder();
        sb.append("---\ndescription: Curated workflow skills for this project\nglobs: **/*\n---\n\n");
        if (skills.isEmpty()) {
            sb.append(SKILLS_PLACEHOLDER);
            return sb.toString();
        }
        skills.forEach(s -> {
            sb.append("#### ").append(skillTitle(s)).append("\n\n");
            if (s.trigger() != null && !s.trigger().isBlank()) {
                sb.append("**Trigger:** ").append(s.trigger().strip()).append("\n\n");
            }
            if (s.steps() != null && !s.steps().isEmpty()) {
                sb.append("**Steps:**\n");
                int i = 1;
                for (var step : s.steps()) {
                    sb.append(i++).append(". ").append(step).append("\n");
                }
                sb.append("\n");
            }
            if (s.outputExpectations() != null && !s.outputExpectations().isEmpty()) {
                sb.append("**Expected Output:**\n");
                s.outputExpectations().forEach(o -> sb.append("- ").append(o).append("\n"));
                sb.append("\n");
            }
            if (s.notes() != null && !s.notes().isBlank()) {
                sb.append("**Notes:** ").append(s.notes().strip()).append("\n\n");
            }
        });
        return sb.toString();
    }

    private String buildCursorChecklists(List<Checklist> checklists) {
        var sb = new StringBuilder();
        sb.append("---\ndescription: Verification checklists for this project\nglobs: **/*\n---\n\n");
        checklists.forEach(c -> {
            sb.append("#### ").append(c.title() != null ? c.title() : titleFromId(c.id())).append("\n\n");
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

    private String buildCursorPrompts(List<Prompt> prompts) {
        var sb = new StringBuilder();
        sb.append("---\ndescription: Reusable prompt templates for this project\nglobs: **/*\n---\n\n");
        prompts.forEach(p -> {
            sb.append("#### ").append(p.title() != null ? p.title() : titleFromId(p.id())).append("\n\n");
            if (p.template() != null && !p.template().isBlank()) {
                sb.append("```\n").append(p.template().strip()).append("\n```\n\n");
            }
        });
        return sb.toString();
    }

    private String buildCursorStackRules(ProjectContext ctx) {
        return """
            ---
            description: Stack and dependency context
            globs: **/*
            ---

            Stack: %s

            Dependencies: %s
            """.formatted(
                ctx.detectedStack(),
                ctx.dependencies().isEmpty()
                    ? "none detected"
                    : String.join(", ", ctx.dependencies().subList(0, Math.min(ctx.dependencies().size(), 15)))
            );
    }

    private String formatFileList(List<String> files, int limit) {
        var sb = new StringBuilder();
        files.stream().limit(limit).forEach(f -> sb.append("- `").append(f).append("`\n"));
        if (files.size() > limit) {
            sb.append("- ... and ").append(files.size() - limit).append(" more\n");
        }
        return sb.toString();
    }

    private static String safe(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
