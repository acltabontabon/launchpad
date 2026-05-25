package com.acltabontabon.launchpad.template.rendering;

import java.util.ArrayList;
import java.util.Set;

public final class PointerBlocks {

    private PointerBlocks() {}

    /**
     * Renders the `## Generated context` block as pointers to companion files
     * that were actually emitted. Returns an empty string when no companion
     * file qualifies, so the section is omitted entirely on naked projects
     * with no standards / no rules / no skills configured.
     */
    public static String renderGeneratedContextBlock(Set<String> companionPaths) {
        var entries = new ArrayList<String>();
        if (companionPaths.contains(".ai/index.md"))
            entries.add("- `.ai/index.md` - file map for this directory");
        if (companionPaths.contains(".ai/stack.md"))
            entries.add("- `.ai/stack.md` - stack and dependency notes");
        if (companionPaths.contains(".ai/engineering-rules.md"))
            entries.add("- `.ai/engineering-rules.md` - team coding rules");
        if (companionPaths.contains(".ai/checklists.md"))
            entries.add("- `.ai/checklists.md` - verification checklists");
        boolean hasSkillFile = companionPaths.stream().anyMatch(p -> p.startsWith(".claude/skills/"));
        if (hasSkillFile)
            entries.add("- `.claude/skills/<skill-id>/SKILL.md` - invoke via `/<skill-id>`");

        if (entries.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append("## Generated context\n\n");
        sb.append("Before making changes, read:\n\n");
        entries.forEach(e -> sb.append(e).append("\n"));
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Cursor analogue of {@link #renderGeneratedContextBlock(Set)}.
     * Renders a `## Standards` pointer list keyed on the `.cursor/rules/*.mdc`
     * companion files actually emitted, so we never point at a file we did not
     * write.
     */
    public static String renderCursorStandardsBlock(Set<String> companionPaths) {
        var entries = new ArrayList<String>();
        if (companionPaths.contains(".cursor/rules/engineering.mdc"))
            entries.add("- **Engineering rules:** see `.cursor/rules/engineering.mdc`");
        if (companionPaths.contains(".cursor/rules/skills.mdc"))
            entries.add("- **Workflow skills:** see `.cursor/rules/skills.mdc`");
        if (companionPaths.contains(".cursor/rules/stack.mdc"))
            entries.add("- **Stack and dependencies:** see `.cursor/rules/stack.mdc`");
        if (companionPaths.contains(".cursor/rules/checklists.mdc"))
            entries.add("- **Checklists:** see `.cursor/rules/checklists.mdc`");

        if (entries.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("## Standards\n\n");
        sb.append("Canonical sources for this project's engineering standards. The primary file ")
          .append("references them so a single edit propagates everywhere.\n\n");
        entries.forEach(e -> sb.append(e).append("\n"));
        sb.append("\n");
        return sb.toString();
    }
}
