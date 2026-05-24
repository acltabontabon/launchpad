package com.acltabontabon.launchpad.scanner;

import com.acltabontabon.launchpad.scanner.doc.DocumentationIndex;
import com.acltabontabon.launchpad.springboot.maven.MavenProfile;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Result of a project scan. Carries a structured stack profile, a dependency
 * list, package-grouped structure summary, entry points, short excerpts of
 * key build / config files, detected HTTP endpoints, detected Maven build
 * profiles, and a README intro paragraph when one is available.
 * <p>
 * `toPromptString` renders a compact view of these facts for any LLM call
 * that needs broad context (rules / skills paths). The CLAUDE.md primary
 * file no longer goes through that single mega-prompt - it is assembled
 * deterministically with bounded per-section synthesis instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectContext(
    String name,
    String rootPath,
    StackProfile stack,
    List<String> sourceFiles,            // full list - used for hallucination check, not the prompt
    List<String> testClassNames,
    Map<String, String> entryPoints,
    List<Dependency> dependencies,
    Map<String, String> fileSnippets,    // key build / config file → first N lines
    List<PackageSummary> packageSummaries,
    String existingContextSummary,       // first ~800 chars of existing CLAUDE.md / .cursorrules, or null
    DocumentationIndex documentation,    // discovered docs (MkDocs / Antora / plain / none)
    List<Endpoint> endpoints,            // HTTP routes detected from controller sources
    String readmeIntro,                  // first prose paragraph of README.md after the title, or ""
    String pomDescription,               // pom.xml <description> text, or ""
    List<MavenProfile> mavenProfiles,    // detected Maven <profile> blocks
    // ── Round-6 evidence packets - bounded source snippets per section ──
    Map<String, String> controllerSources,    // controller path → first ~120 lines
    Map<String, String> profileRawXml,        // profile id → raw <profile> body
    Map<String, String> readmeSections,       // README section name → body
    Map<String, String> scriptsCatalog,       // script id → first comment line
    Map<String, String> packageRepresentatives, // package path → first ~60 lines of a representative file
    // ── Round-8: Spring Actuator endpoints + deterministic notes ──
    List<Endpoint> actuatorEndpoints,         // Detected actuator routes (GET /actuator/<name>)
    Map<String, String> actuatorNotes         // "GET /actuator/<name>" → deterministic note
) {

    /** Approximate character budget for the prompt-side rendering of this context. */
    private static final int DEFAULT_BUDGET_CHARS = 6_000;

    /** Legacy 15-arg overload preserved for callers built before the round-6 evidence packets. */
    public ProjectContext(
        String name,
        String rootPath,
        StackProfile stack,
        List<String> sourceFiles,
        List<String> testClassNames,
        Map<String, String> entryPoints,
        List<Dependency> dependencies,
        Map<String, String> fileSnippets,
        List<PackageSummary> packageSummaries,
        String existingContextSummary,
        DocumentationIndex documentation,
        List<Endpoint> endpoints,
        String readmeIntro,
        String pomDescription,
        List<MavenProfile> mavenProfiles
    ) {
        this(name, rootPath, stack, sourceFiles, testClassNames, entryPoints, dependencies,
            fileSnippets, packageSummaries, existingContextSummary, documentation,
            endpoints, readmeIntro, pomDescription, mavenProfiles,
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(), Map.of());
    }

    /** Legacy 11-arg overload preserved for test fixtures that predate the round-4 redesign. */
    public ProjectContext(
        String name,
        String rootPath,
        StackProfile stack,
        List<String> sourceFiles,
        List<String> testClassNames,
        Map<String, String> entryPoints,
        List<Dependency> dependencies,
        Map<String, String> fileSnippets,
        List<PackageSummary> packageSummaries,
        String existingContextSummary,
        DocumentationIndex documentation
    ) {
        this(name, rootPath, stack, sourceFiles, testClassNames, entryPoints, dependencies,
            fileSnippets, packageSummaries, existingContextSummary, documentation,
            List.of(), "", "", List.of(),
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(), Map.of());
    }

    /** Legacy 10-arg overload preserved for older test fixtures. */
    public ProjectContext(
        String name,
        String rootPath,
        StackProfile stack,
        List<String> sourceFiles,
        List<String> testClassNames,
        Map<String, String> entryPoints,
        List<Dependency> dependencies,
        Map<String, String> fileSnippets,
        List<PackageSummary> packageSummaries,
        String existingContextSummary
    ) {
        this(name, rootPath, stack, sourceFiles, testClassNames, entryPoints, dependencies,
            fileSnippets, packageSummaries, existingContextSummary, DocumentationIndex.none(),
            List.of(), "", "", List.of(),
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(), Map.of());
    }

    public ProjectContext {
        if (documentation == null) documentation = DocumentationIndex.none();
        if (endpoints == null) endpoints = List.of();
        if (readmeIntro == null) readmeIntro = "";
        if (pomDescription == null) pomDescription = "";
        if (mavenProfiles == null) mavenProfiles = List.of();
        if (controllerSources == null) controllerSources = Map.of();
        if (profileRawXml == null) profileRawXml = Map.of();
        if (readmeSections == null) readmeSections = Map.of();
        if (scriptsCatalog == null) scriptsCatalog = Map.of();
        if (packageRepresentatives == null) packageRepresentatives = Map.of();
        if (actuatorEndpoints == null) actuatorEndpoints = List.of();
        if (actuatorNotes == null) actuatorNotes = Map.of();
    }

    public String toPromptString() {
        return toPromptString(DEFAULT_BUDGET_CHARS);
    }

    public String toPromptString(int budgetChars) {
        var sb = new StringBuilder();

        sb.append("# Project: ").append(name).append("\n");
        sb.append("Stack: ").append(stack.displayName()).append("\n");
        if (stack.framework() != null) {
            sb.append("Framework: ").append(stack.framework()).append("\n");
        }
        if (stack.springProfile() != null) {
            var facets = stack.springProfile().facets();
            if (!facets.isEmpty()) {
                sb.append("Spring sub-stack: ").append(String.join(", ", facets)).append("\n");
            }
        }
        sb.append("Source file count: ").append(sourceFiles.size()).append("\n");
        if (!testClassNames.isEmpty()) {
            sb.append("Test file count: ").append(testClassNames.size()).append("\n");
        }
        sb.append("\n");

        if (!entryPoints.isEmpty()) {
            sb.append("## Entry Points\n");
            entryPoints.forEach((k, v) -> sb.append("- ").append(k).append(": `").append(v).append("`\n"));
            sb.append("\n");
        }

        if (!packageSummaries.isEmpty()) {
            sb.append("## Source Structure (top packages by file count)\n");
            for (var pkg : packageSummaries) {
                sb.append("- `").append(pkg.path()).append("/` (").append(pkg.fileCount()).append(" files)");
                if (!pkg.sampleSymbols().isEmpty()) {
                    sb.append(" - ").append(String.join(", ", pkg.sampleSymbols()));
                }
                sb.append("\n");
                if (sb.length() > budgetChars * 4 / 10) break;
            }
            sb.append("\n");
        }

        if (!dependencies.isEmpty()) {
            sb.append("## Dependencies (").append(dependencies.size()).append(" total)\n");
            int limit = Math.min(dependencies.size(), 40);
            for (int i = 0; i < limit; i++) {
                sb.append("- ").append(dependencies.get(i).display()).append("\n");
            }
            if (dependencies.size() > limit) {
                sb.append("- ... and ").append(dependencies.size() - limit).append(" more\n");
            }
            sb.append("\n");
        }

        if (documentation != null && !documentation.isEmpty()) {
            sb.append("## Documentation\n");
            sb.append("Format: ").append(documentation.format()).append("\n");
            if (documentation.siteName() != null && !documentation.siteName().isBlank()) {
                sb.append("Site: ").append(documentation.siteName()).append("\n");
            }
            sb.append("Pages: ").append(documentation.pages().size()).append("\n\n");
        }

        if (existingContextSummary != null && !existingContextSummary.isBlank()
            && sb.length() < budgetChars - 600) {
            sb.append("## Existing Context (already documented - focus on what's missing, don't duplicate)\n\n```\n");
            sb.append(truncate(existingContextSummary, 600));
            sb.append("\n```\n\n");
        }

        if (sb.length() > budgetChars) {
            sb.setLength(budgetChars);
            sb.append("\n... (truncated to fit prompt budget)\n");
        }
        return sb.toString();
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, Math.max(0, maxChars)) + "\n... (truncated)";
    }
}
