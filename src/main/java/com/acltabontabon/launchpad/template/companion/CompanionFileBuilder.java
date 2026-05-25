package com.acltabontabon.launchpad.template.companion;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.template.GeneratedFile;
import com.acltabontabon.launchpad.template.rendering.StandardsRendering;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CompanionFileBuilder {

    public List<GeneratedFile> build(ProjectContext ctx,
                                     List<Rule> rules, List<Skill> skills,
                                     List<Checklist> checklists) {
        var out = new ArrayList<GeneratedFile>();
        out.add(new GeneratedFile(".ai/index.md",
            StandardsRendering.buildAiIndex(ctx, skills, !checklists.isEmpty()),
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
        skills.forEach(s -> out.add(new GeneratedFile(
            ".claude/skills/" + s.id() + "/SKILL.md",
            StandardsRendering.buildClaudeSkillFile(s),
            GeneratedFile.FileKind.SKILL
        )));
        return out;
    }
}
