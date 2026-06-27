package com.acltabontabon.launchpad.template;

public record GeneratedFile(
    String relativePath,
    String content,
    FileKind kind
) {

    public enum FileKind {
        CONTEXT,    // AGENTS.md (the primary agent-instructions file)
        RULES,      // engineering rules files
        SKILL,      // one skill, vendor-native projection file (e.g. .claude/skills/<id>/SKILL.md)
        SKILLS,     // canonical aggregated skills companion (.ai/skills.md)
        INDEX,      // .ai/index.md, directory indexes
        CONFIG      // vendor tool config that points the tool at canonical output (e.g. .gemini/settings.json)
    }

    public String filename() {
        return relativePath.contains("/")
            ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
            : relativePath;
    }
}
