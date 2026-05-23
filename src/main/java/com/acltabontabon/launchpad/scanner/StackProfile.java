package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured view of a project's tech stack. Replaces the legacy single-string
 * "Java / Maven" form so prompts and rules can branch on framework, not just
 * build tool.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StackProfile(
    String language,        // "Java", "TypeScript", "Python", "Rust", "Go", "Ruby", ...
    String buildTool,       // "Maven", "Gradle", "npm", "pip", "cargo", ...
    String framework,       // "Spring Boot", "Next.js", "Django", "FastAPI", "Rails", ... or null
    List<String> runtimeHints
) {

    public static StackProfile unknown() {
        return new StackProfile("Unknown", null, null, List.of());
    }

    /** Single-line human label, e.g. "Spring Boot / Java / Maven". */
    public String displayName() {
        var parts = new ArrayList<String>();
        if (framework != null && !framework.isBlank()) parts.add(framework);
        if (language != null && !language.isBlank()) parts.add(language);
        if (buildTool != null && !buildTool.isBlank()) parts.add(buildTool);
        return parts.isEmpty() ? "Unknown" : String.join(" / ", parts);
    }
}
