package com.acltabontabon.launchpad.template.companion;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Prompt;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.template.ContextTarget;
import com.acltabontabon.launchpad.template.GeneratedFile;
import com.acltabontabon.launchpad.template.rendering.StandardsRendering;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CompanionFileBuilder {

    public List<GeneratedFile> build(ContextTarget target, ProjectContext ctx,
                                     List<Rule> rules, List<Skill> skills,
                                     List<Checklist> checklists, List<Prompt> prompts,
                                     String llmBody) {
        return switch (target) {
            case CLAUDE -> collectClaudeCompanions(ctx, rules, skills, checklists, prompts, llmBody);
            case CURSOR -> collectCursorCompanions(ctx, rules, skills, checklists, prompts, llmBody);
        };
    }

    /**
     * Builds the ordered list of `.ai/*` and `.claude/skills/*` companion
     * files for the Claude target. Conditional entries (checklists, prompts,
     * project notes, skills) appear only when their source data is non-empty
     * so the primary file's `## Generated context` block can be gated on the
     * real output set.
     */
    private List<GeneratedFile> collectClaudeCompanions(
        ProjectContext ctx,
        List<Rule> rules, List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        String llmSkills
    ) {
        var out = new ArrayList<GeneratedFile>();
        out.add(new GeneratedFile(".ai/index.md",
            StandardsRendering.buildAiIndex(ctx, skills, !checklists.isEmpty(), !prompts.isEmpty(),
                llmSkills != null && !llmSkills.isBlank()),
            GeneratedFile.FileKind.INDEX));
        if (!rules.isEmpty()) {
            out.add(new GeneratedFile(".ai/engineering-rules.md",
                StandardsRendering.buildEngineeringRulesMd(rules),
                GeneratedFile.FileKind.RULES));
        }
        out.add(new GeneratedFile(".ai/stack.md",
            StandardsRendering.buildStackMd(ctx),
            GeneratedFile.FileKind.CONTEXT));
        if (!checklists.isEmpty()) {
            out.add(new GeneratedFile(".ai/checklists.md",
                StandardsRendering.buildChecklistsMd(checklists),
                GeneratedFile.FileKind.RULES));
        }
        if (!prompts.isEmpty()) {
            out.add(new GeneratedFile(".ai/prompts.md",
                StandardsRendering.buildPromptsMd(prompts),
                GeneratedFile.FileKind.RULES));
        }
        if (llmSkills != null && !llmSkills.isBlank()) {
            out.add(new GeneratedFile(".ai/project-notes.md",
                StandardsRendering.buildProjectNotesMd(ctx, llmSkills),
                GeneratedFile.FileKind.CONTEXT));
        }
        skills.forEach(s -> out.add(new GeneratedFile(
            ".claude/skills/" + s.id() + "/SKILL.md",
            StandardsRendering.buildClaudeSkillFile(s),
            GeneratedFile.FileKind.SKILL
        )));
        return out;
    }

    /**
     * Cursor's `.cursor/rules/*.mdc` siblings. Conditional entries appear only
     * when their source data is non-empty so the primary file's `## Standards`
     * pointer block can be gated on the real output set, mirroring the Claude
     * companion collector.
     */
    private List<GeneratedFile> collectCursorCompanions(
        ProjectContext ctx,
        List<Rule> rules, List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        String llmRules
    ) {
        var out = new ArrayList<GeneratedFile>();
        out.add(new GeneratedFile(".cursor/rules/engineering.mdc",
            StandardsRendering.buildCursorEngineeringRules(rules),
            GeneratedFile.FileKind.RULES));
        out.add(new GeneratedFile(".cursor/rules/skills.mdc",
            StandardsRendering.buildCursorSkills(skills),
            GeneratedFile.FileKind.RULES));
        out.add(new GeneratedFile(".cursor/rules/stack.mdc",
            StandardsRendering.buildCursorStackRules(ctx),
            GeneratedFile.FileKind.CONTEXT));
        if (!checklists.isEmpty()) {
            out.add(new GeneratedFile(".cursor/rules/checklists.mdc",
                StandardsRendering.buildCursorChecklists(checklists),
                GeneratedFile.FileKind.RULES));
        }
        if (!prompts.isEmpty()) {
            out.add(new GeneratedFile(".cursor/rules/prompts.mdc",
                StandardsRendering.buildCursorPrompts(prompts),
                GeneratedFile.FileKind.RULES));
        }
        if (llmRules != null && !llmRules.isBlank()) {
            out.add(new GeneratedFile(".cursor/rules/project-notes.mdc",
                StandardsRendering.buildCursorProjectNotes(ctx, llmRules),
                GeneratedFile.FileKind.CONTEXT));
        }
        return out;
    }
}
