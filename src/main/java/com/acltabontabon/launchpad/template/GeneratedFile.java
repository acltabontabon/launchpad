package com.acltabontabon.launchpad.template;

public record GeneratedFile(
    String relativePath,
    String content,
    FileKind kind
) {

    public enum FileKind {
        CONTEXT,    // CLAUDE.md, .cursorrules
        RULES,      // engineering rules files
        SKILL,      // skill / command definitions
        INDEX       // .ai/index.md, directory indexes
    }

    public String filename() {
        return relativePath.contains("/")
            ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
            : relativePath;
    }
}
