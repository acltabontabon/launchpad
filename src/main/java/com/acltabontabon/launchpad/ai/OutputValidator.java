package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks LLM output for the obvious failure modes:
 *   1. Empty / suspiciously short content
 *   2. Missing structural markers the prompt asked for (## headings)
 *   3. Backtick-quoted file paths that don't exist in the scanned project
 *      (hallucination - common with smaller local models)
 * Returns a list of human-readable warnings. Empty list = passed.
 */
public final class OutputValidator {

    private static final int MIN_CONTENT_CHARS = 120;
    private static final int MAX_HALLUCINATION_WARNINGS = 5;
    private static final Pattern BACKTICKED_PATH = Pattern.compile(
        "`([A-Za-z0-9_\\-./]+\\.(java|kt|py|ts|tsx|js|jsx|go|rs|cs|rb|swift|xml|yml|yaml|toml|json|md|properties))`");

    public List<String> validate(String content, ProjectContext ctx, List<String> requiredHeadings) {
        var warnings = new ArrayList<String>();
        if (content == null || content.isBlank()) {
            warnings.add("empty model output");
            return warnings;
        }
        if (content.length() < MIN_CONTENT_CHARS) {
            warnings.add("suspiciously short output (" + content.length() + " chars)");
        }
        for (String heading : requiredHeadings) {
            if (!content.contains(heading)) {
                warnings.add("missing required section: " + heading);
            }
        }
        addHallucinationWarnings(content, ctx, warnings);
        return warnings;
    }

    /**
     * Returns a {@link CleanResult} carrying the input content with hallucinated
     * file references removed:
     *   - If a hallucinated ref appears on a markdown bullet line ({@code "- "}
     *     or {@code "* "}), the whole line is dropped. Bullets that exist only
     *     to enumerate a fake file have no salvageable content.
     *   - Otherwise the offending backtick token is replaced with
     *     {@code "an unrelated file"} so the surrounding prose still flows.
     * Returns a count of stripped references alongside the cleaned content.
     */
    public CleanResult cleanHallucinations(String content, ProjectContext ctx) {
        if (content == null || content.isBlank()) return new CleanResult(content, 0);
        Set<String> sourceSet = new HashSet<>(ctx.sourceFiles());
        Set<String> basenames = buildBasenameAllowlist(ctx);

        var lines = content.split("\n", -1);
        var out = new StringBuilder();
        int stripped = 0;
        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            var hallucinated = findHallucinatedRefs(line, sourceSet, basenames);
            if (hallucinated.isEmpty()) {
                out.append(line);
            } else if (isBulletLine(line)) {
                // Drop the whole bullet line - it's almost always a useless
                // "  - FakeService.java: handles..." entry.
                stripped += hallucinated.size();
                continue;
            } else {
                // Replace each hallucinated backtick token in-place; keep the
                // line so prose flows.
                var scrubbed = line;
                for (var ref : hallucinated) {
                    scrubbed = scrubbed.replace("`" + ref + "`", "an unrelated file");
                    stripped++;
                }
                out.append(scrubbed);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return new CleanResult(out.toString(), stripped);
    }

    public record CleanResult(String content, int strippedCount) {}

    private static boolean isBulletLine(String line) {
        var trimmed = line.stripLeading();
        return trimmed.startsWith("- ") || trimmed.startsWith("* ")
            || trimmed.startsWith("• ") || trimmed.matches("^\\d+\\.\\s.*");
    }

    /**
     * Common build / config files that every reasonable project might cite.
     * Allowlisting these prevents false-positive "hallucination" warnings when
     * the model mentions `pom.xml`, `package.json`, etc. - which are not in
     * the scanned source file list but are absolutely real.
     */
    private static final Set<String> WELL_KNOWN_FILES = Set.of(
        // AI / docs
        "CLAUDE.md", ".cursorrules", "README.md", "LICENSE", "CHANGELOG.md",
        // Java / JVM
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        "settings.gradle.kts", "gradle.properties", "application.properties",
        "application.yml", "application.yaml",
        // Node / TS
        "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
        "tsconfig.json", "tsconfig.base.json", ".eslintrc", ".prettierrc",
        "next.config.js", "next.config.ts", "vite.config.ts", "vite.config.js",
        // Python
        "requirements.txt", "setup.py", "pyproject.toml", "Pipfile",
        // Rust / Go / Ruby / .NET
        "Cargo.toml", "Cargo.lock", "go.mod", "go.sum",
        "Gemfile", "Gemfile.lock", "Rakefile",
        // Docker / infra
        "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
        ".gitignore", ".dockerignore", ".env", ".env.example"
    );

    private static Set<String> buildBasenameAllowlist(ProjectContext ctx) {
        Set<String> basenames = new HashSet<>();
        for (var p : ctx.sourceFiles()) {
            int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
            basenames.add(slash < 0 ? p : p.substring(slash + 1));
        }
        for (var d : ctx.dependencies()) basenames.add(d.name());
        basenames.addAll(WELL_KNOWN_FILES);
        return basenames;
    }

    private static List<String> findHallucinatedRefs(String line, Set<String> sourceSet, Set<String> basenames) {
        var hits = new ArrayList<String>();
        Matcher m = BACKTICKED_PATH.matcher(line);
        while (m.find()) {
            String ref = m.group(1);
            String basename = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
            if (sourceSet.contains(ref) || basenames.contains(basename)) continue;
            hits.add(ref);
        }
        return hits;
    }

    private static void addHallucinationWarnings(String content, ProjectContext ctx, List<String> warnings) {
        Set<String> sourceSet = new HashSet<>(ctx.sourceFiles());
        Set<String> basenames = buildBasenameAllowlist(ctx);

        Matcher m = BACKTICKED_PATH.matcher(content);
        var seen = new HashSet<String>();
        int reported = 0;
        while (m.find() && reported < MAX_HALLUCINATION_WARNINGS) {
            String ref = m.group(1);
            if (!seen.add(ref)) continue;
            String basename = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
            if (sourceSet.contains(ref) || basenames.contains(basename)) continue;
            warnings.add("model referenced `" + ref + "` which is not in the scanned project");
            reported++;
        }
    }
}
