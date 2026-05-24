package com.acltabontabon.launchpad.scanner;

import com.acltabontabon.launchpad.springboot.runtime.SpringProfile;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured view of a project's tech stack.
 *
 * <p>Today {@link ProjectSupportDetector} only admits Spring Boot Java +
 * Maven projects, so by the time a {@code StackProfile} reaches downstream
 * phases the {@code language} / {@code buildTool} / {@code framework} fields
 * are effectively constants (Java / Maven / Spring Boot). They are kept as
 * fields because display, JSON persistence (via {@link com.fasterxml.jackson.annotation.JsonIgnoreProperties}),
 * and template rendering still read them; a follow-up pass may collapse them
 * into a Spring-specific profile model once those readers are tightened.
 *
 * <p>{@code springProfile} carries Spring sub-stack signals (web,
 * persistence, AI, ...) used by the prompt composer to pull the right facet
 * files.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StackProfile(
    String language,
    String buildTool,
    String framework,
    List<String> runtimeHints,
    SpringProfile springProfile
) {

    public StackProfile(String language, String buildTool, String framework, List<String> runtimeHints) {
        this(language, buildTool, framework, runtimeHints, null);
    }

    public static StackProfile unknown() {
        return new StackProfile("Unknown", null, null, List.of(), null);
    }

    public StackProfile withSpringProfile(SpringProfile sp) {
        return new StackProfile(language, buildTool, framework, runtimeHints, sp);
    }

    public StackProfile withFramework(String newFramework) {
        return new StackProfile(language, buildTool, newFramework, runtimeHints, springProfile);
    }

    public boolean isSpring() {
        return framework != null && framework.toLowerCase().contains("spring");
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
