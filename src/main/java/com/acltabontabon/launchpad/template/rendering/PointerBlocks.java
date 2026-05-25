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

        if (entries.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append("## Generated context\n\n");
        sb.append("Before making changes, read:\n\n");
        entries.forEach(e -> sb.append(e).append("\n"));
        sb.append("\n");
        return sb.toString();
    }

}
