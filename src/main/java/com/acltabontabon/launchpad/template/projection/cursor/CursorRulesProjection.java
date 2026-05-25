package com.acltabontabon.launchpad.template.projection.cursor;

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
 * Projects the canonical engineering model into Cursor's {@code .cursor/rules/}
 * directory as MDC files. Opt-in via {@code projections: ["claude", "cursor"]}
 * in {@code standards-pack.yml} or by ticking Cursor in the TUI picker.
 *
 * <p>Emits:
 * <ul>
 *   <li>{@code .cursor/rules/engineering.mdc} - rules bundle ({@code alwaysApply})</li>
 *   <li>{@code .cursor/rules/<skill-id>.mdc} - one file per canonical skill
 *       (description-attached based on the canonical trigger)</li>
 *   <li>{@code .cursor/rules/checklists.mdc} - if checklists exist</li>
 * </ul>
 */
@Component
public class CursorRulesProjection implements AgentProjection {

    public static final String ID = "cursor";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Cursor";
    }

    @Override
    public String description() {
        return "Emits .cursor/rules/*.mdc rule files for Cursor's rules system";
    }

    @Override
    public List<GeneratedFile> project(ProjectContext ctx, List<Rule> rules,
                                       List<Skill> skills, List<Checklist> checklists) {
        var out = new ArrayList<GeneratedFile>();
        if (rules != null && !rules.isEmpty()) {
            out.add(new GeneratedFile(
                ".cursor/rules/engineering.mdc",
                CursorMdcRenderer.renderEngineering(rules),
                GeneratedFile.FileKind.RULES
            ));
        }
        if (skills != null) {
            for (var skill : skills) {
                out.add(new GeneratedFile(
                    ".cursor/rules/" + skill.id() + ".mdc",
                    CursorMdcRenderer.renderSkill(skill),
                    GeneratedFile.FileKind.SKILL
                ));
            }
        }
        if (checklists != null && !checklists.isEmpty()) {
            out.add(new GeneratedFile(
                ".cursor/rules/checklists.mdc",
                CursorMdcRenderer.renderChecklists(checklists),
                GeneratedFile.FileKind.RULES
            ));
        }
        return out;
    }
}
