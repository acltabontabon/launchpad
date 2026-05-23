package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured view of a project's tech stack. Replaces the legacy single-string
 * "Java / Maven" form so prompts and rules can branch on framework, not just
 * build tool.
 * <p>
 * {@code springProfile} is populated only when {@link #framework()} is a Spring
 * variant; {@code databricksProfile} only when it is a Databricks project.
 * Both carry sub-stack signals (web/persistence/ai/... and
 * terraform/dlt/python/sql respectively) used by the prompt composer to pull
 * the right facet files.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StackProfile(
    String language,        // "Java", "TypeScript", "Python", "Rust", "Go", "Ruby", ...
    String buildTool,       // "Maven", "Gradle", "npm", "pip", "cargo", ...
    String framework,       // "Spring Boot", "Databricks", "Next.js", ... or null
    List<String> runtimeHints,
    SpringProfile springProfile,
    DatabricksProfile databricksProfile
) {

    public StackProfile(String language, String buildTool, String framework, List<String> runtimeHints) {
        this(language, buildTool, framework, runtimeHints, null, null);
    }

    public static StackProfile unknown() {
        return new StackProfile("Unknown", null, null, List.of(), null, null);
    }

    public StackProfile withSpringProfile(SpringProfile sp) {
        return new StackProfile(language, buildTool, framework, runtimeHints, sp, databricksProfile);
    }

    public StackProfile withDatabricksProfile(DatabricksProfile dp) {
        return new StackProfile(language, buildTool, framework, runtimeHints, springProfile, dp);
    }

    public StackProfile withFramework(String newFramework) {
        return new StackProfile(language, buildTool, newFramework, runtimeHints, springProfile, databricksProfile);
    }

    public boolean isSpring() {
        return framework != null && framework.toLowerCase().contains("spring");
    }

    public boolean isDatabricks() {
        return framework != null && framework.toLowerCase().contains("databricks");
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
