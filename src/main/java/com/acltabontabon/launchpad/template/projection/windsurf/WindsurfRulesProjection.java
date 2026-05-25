package com.acltabontabon.launchpad.template.projection.windsurf;

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
 * Projects the canonical engineering model into Windsurf's
 * {@code .windsurf/rules/} directory as per-rule markdown files. Opt-in via
 * {@code projections: ["claude", "windsurf"]} in {@code standards-pack.yml}
 * or by ticking Windsurf in the TUI picker.
 *
 * <p>Emits:
 * <ul>
 *   <li>{@code .windsurf/rules/engineering.md} - rules bundle ({@code always_on})</li>
 *   <li>{@code .windsurf/rules/<skill-id>.md} - one file per canonical skill
 *       ({@code model_decision} with the canonical trigger as description)</li>
 *   <li>{@code .windsurf/rules/checklists.md} - if checklists exist</li>
 * </ul>
 */
@Component
public class WindsurfRulesProjection implements AgentProjection {

    public static final String ID = "windsurf";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Windsurf";
    }

    @Override
    public String description() {
        return "Emits .windsurf/rules/*.md rule files for Windsurf's rules system";
    }

    @Override
    public List<GeneratedFile> project(ProjectContext ctx, List<Rule> rules,
                                       List<Skill> skills, List<Checklist> checklists) {
        var out = new ArrayList<GeneratedFile>();
        if (rules != null && !rules.isEmpty()) {
            out.add(new GeneratedFile(
                ".windsurf/rules/engineering.md",
                WindsurfRuleRenderer.renderEngineering(rules),
                GeneratedFile.FileKind.RULES
            ));
        }
        if (skills != null) {
            for (var skill : skills) {
                out.add(new GeneratedFile(
                    ".windsurf/rules/" + skill.id() + ".md",
                    WindsurfRuleRenderer.renderSkill(skill),
                    GeneratedFile.FileKind.SKILL
                ));
            }
        }
        if (checklists != null && !checklists.isEmpty()) {
            out.add(new GeneratedFile(
                ".windsurf/rules/checklists.md",
                WindsurfRuleRenderer.renderChecklists(checklists),
                GeneratedFile.FileKind.RULES
            ));
        }
        return out;
    }
}
