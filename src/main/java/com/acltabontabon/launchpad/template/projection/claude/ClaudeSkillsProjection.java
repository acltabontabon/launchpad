package com.acltabontabon.launchpad.template.projection.claude;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.template.GeneratedFile;
import com.acltabontabon.launchpad.template.projection.AgentProjection;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Projects canonical {@link Skill} records into Claude Code's slash-command
 * discovery layout at {@code .claude/skills/<id>/SKILL.md}. Owns the path
 * convention; the engine does not.
 *
 * <p>Default-on (resolved by {@code StandardsLoader#loadProjectionIds}
 * when a project's manifest does not list {@code projections:}) so existing
 * users keep the Claude slash-command experience without configuration.
 */
@Component
public class ClaudeSkillsProjection implements AgentProjection {

    public static final String ID = "claude";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<GeneratedFile> project(ProjectContext ctx, List<Rule> rules,
                                       List<Skill> skills, List<Checklist> checklists) {
        if (skills == null || skills.isEmpty()) return List.of();
        var out = new ArrayList<GeneratedFile>(skills.size());
        for (var skill : skills) {
            out.add(new GeneratedFile(
                ".claude/skills/" + skill.id() + "/SKILL.md",
                ClaudeSkillRenderer.render(skill),
                GeneratedFile.FileKind.SKILL
            ));
        }
        return out;
    }
}
