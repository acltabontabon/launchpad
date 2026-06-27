package com.acltabontabon.launchpad.template.projection.gemini;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.template.GeneratedFile;
import com.acltabontabon.launchpad.template.projection.AgentProjection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Makes Gemini CLI read Launchpad's canonical {@code AGENTS.md} directly,
 * instead of emitting a separate (and redundant) {@code GEMINI.md}.
 *
 * <p>Gemini CLI loads its project context from a configurable file name
 * (default {@code GEMINI.md}). This projection writes a minimal
 * {@code .gemini/settings.json} that sets {@code context.fileName} to
 * {@code AGENTS.md}, so Gemini reads the single source-of-truth contract with
 * no duplicated content to drift out of sync. Opt-in via
 * {@code projections: ["claude", "gemini"]} in {@code standards-pack.yml} or by
 * ticking Gemini CLI in the TUI picker.
 *
 * <p>The settings file carries no JSON comment markers, so an existing
 * hand-authored {@code .gemini/settings.json} is never clobbered (the write
 * layer classifies it {@code SKIP}); developers who already maintain that file
 * add {@code context.fileName: "AGENTS.md"} themselves.
 */
@Component
public class GeminiProjection implements AgentProjection {

    public static final String ID = "gemini";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Gemini CLI";
    }

    @Override
    public String description() {
        return "Points Gemini CLI at the canonical AGENTS.md via .gemini/settings.json";
    }

    @Override
    public List<GeneratedFile> project(ProjectContext ctx, List<Rule> rules,
                                       List<Skill> skills, List<Checklist> checklists) {
        return List.of(new GeneratedFile(
            ".gemini/settings.json",
            GeminiSettings.pointingAtAgentsMd(),
            GeneratedFile.FileKind.CONFIG
        ));
    }
}
