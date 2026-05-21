package com.acltabontabon.launchpad.template;

public enum ContextTarget {

    CLAUDE(
        "Claude Code",
        "Generates CLAUDE.md and .ai/ context files for Claude Code",
        "claude"
    ),
    CURSOR(
        "Cursor",
        "Generates .cursorrules and .cursor/rules/ for Cursor AI",
        "cursor"
    );

    public final String displayName;
    public final String description;
    public final String profileKey;

    ContextTarget(String displayName, String description, String profileKey) {
        this.displayName = displayName;
        this.description = description;
        this.profileKey = profileKey;
    }
}
