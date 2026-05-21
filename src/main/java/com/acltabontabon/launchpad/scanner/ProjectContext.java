package com.acltabontabon.launchpad.scanner;

import java.util.List;
import java.util.Map;

public record ProjectContext(
    String name,
    String rootPath,
    String detectedStack,           // e.g. "Java / Spring Boot / Maven"
    List<String> sourceFiles,       // relative paths of source files scanned
    List<String> testClassNames,    // test class names only (no implementation)
    Map<String, String> entryPoints,// e.g. {"main": "com.example.App", "config": "..."}
    List<String> dependencies,      // from pom.xml / package.json / etc.
    Map<String, String> fileSnippets // relativePath -> first N lines for key files
) {

    /**
     * Formats context into a single prompt-friendly string for the AI model.
     * Keeps token count manageable by summarising rather than dumping raw content.
     */
    public String toPromptString() {
        var sb = new StringBuilder();

        sb.append("# Project: ").append(name).append("\n");
        sb.append("Stack: ").append(detectedStack).append("\n\n");

        sb.append("## Source Files (").append(sourceFiles.size()).append(")\n");
        sourceFiles.forEach(f -> sb.append("- ").append(f).append("\n"));

        if (!testClassNames.isEmpty()) {
            sb.append("\n## Test Classes (names only)\n");
            testClassNames.forEach(t -> sb.append("- ").append(t).append("\n"));
        }

        if (!dependencies.isEmpty()) {
            sb.append("\n## Dependencies\n");
            dependencies.forEach(d -> sb.append("- ").append(d).append("\n"));
        }

        if (!entryPoints.isEmpty()) {
            sb.append("\n## Entry Points\n");
            entryPoints.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        }

        if (!fileSnippets.isEmpty()) {
            sb.append("\n## Key File Excerpts\n");
            fileSnippets.forEach((path, snippet) -> {
                sb.append("\n### ").append(path).append("\n```\n").append(snippet).append("\n```\n");
            });
        }

        return sb.toString();
    }
}
