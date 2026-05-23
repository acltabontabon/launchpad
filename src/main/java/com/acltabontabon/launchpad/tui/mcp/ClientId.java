package com.acltabontabon.launchpad.tui.mcp;

public enum ClientId {
    CLAUDE_DESKTOP("claude-desktop", "Claude Desktop"),
    CLAUDE_CODE("claude-code", "Claude Code"),
    CURSOR("cursor", "Cursor");

    private final String slug;
    private final String displayName;

    ClientId(String slug, String displayName) {
        this.slug = slug;
        this.displayName = displayName;
    }

    public String slug() {
        return slug;
    }

    public String displayName() {
        return displayName;
    }
}
