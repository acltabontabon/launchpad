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
 * Returns a list of human-readable warnings. Empty list = passed.
 * Hallucinated file references are handled by {@link #cleanHallucinations}
 * which strips them silently - the validator does not double-report them.
 */
public final class OutputValidator {

    private static final int MIN_CONTENT_CHARS = 120;
    // Matches any backticked, path-shaped token bearing a trailing extension.
    // Group 1 = full ref, group 2 = extension. The fixed extension allowlist
    // moved out of the regex into KNOWN_EXTENSIONS (plus per-project extensions
    // derived from the scan) - see findHallucinatedRefs. The prefix requires at
    // least one char before the final dot, so a bare `.env` token is never
    // matched and multi-dot paths (`infra/dev.tfvars`) resolve greedily.
    private static final Pattern BACKTICKED_PATH = Pattern.compile(
        "`([A-Za-z0-9_\\-./]+\\.([A-Za-z0-9]+))`");
    // File extensions (lowercase, no dot) we recognize as real source / config
    // formats. A backticked token whose extension is outside this set - and not
    // present among the scanned project's own source files - is left untouched,
    // preserving the validator's conservative bias against false positives.
    private static final Set<String> KNOWN_EXTENSIONS = Set.of(
        // JVM
        "java", "kt", "kts", "gradle", "groovy", "scala",
        // Scripting / web
        "py", "ts", "tsx", "js", "jsx", "mjs", "cjs", "rb", "php",
        // Systems
        "go", "rs", "cs", "swift", "c", "h", "cpp", "hpp",
        // Data / scripts
        "sql", "sh", "bash",
        // Config / markup
        "xml", "yml", "yaml", "toml", "json", "json5", "properties",
        "ini", "env", "conf", "cfg", "tf", "tfvars", "hcl", "lock",
        // Docs / misc
        "md", "txt", "imports");
    // Spring profile config convention: application-<profile>.{properties,yml,yaml}.
    // These are real files in countless Spring projects even when the scanner
    // didn't find one - the model citing them is a convention reference, not a
    // hallucination, so we allowlist the shape.
    private static final Pattern SPRING_PROFILE_CONFIG = Pattern.compile(
        "application-[A-Za-z0-9_-]+\\.(properties|yml|yaml)");

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
        Set<String> extensions = buildExtensionAllowlist(ctx);

        var lines = content.split("\n", -1);
        var out = new StringBuilder();
        int stripped = 0;
        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            var hallucinated = findHallucinatedRefs(line, sourceSet, basenames, extensions);
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
        "AGENTS.md", "CLAUDE.md", ".cursorrules", "README.md", "LICENSE", "CHANGELOG.md",
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
        "Gemfile", "Gemfile.lock", "Rakefile", "poetry.lock",
        // Docker / infra
        "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
        // Note: bare ".env" is intentionally NOT allowlisted - the path regex
        // never matches a bare dotfile token, so the entry only ever affected
        // directory-qualified refs like `config/.env`, which we want flagged.
        ".gitignore", ".dockerignore", ".env.example"
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

    /**
     * Recognized file extensions for this scan: the static {@link #KNOWN_EXTENSIONS}
     * set unioned with the extensions actually present among the project's source
     * files. The union means a project using an uncommon extension still has that
     * extension treated as a real file type, even when it is not hardcoded.
     */
    private static Set<String> buildExtensionAllowlist(ProjectContext ctx) {
        Set<String> extensions = new HashSet<>(KNOWN_EXTENSIONS);
        for (var p : ctx.sourceFiles()) {
            int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
            String basename = slash < 0 ? p : p.substring(slash + 1);
            int dot = basename.lastIndexOf('.');
            if (dot >= 0 && dot < basename.length() - 1) {
                extensions.add(basename.substring(dot + 1).toLowerCase());
            }
        }
        return extensions;
    }

    private static List<String> findHallucinatedRefs(
        String line, Set<String> sourceSet, Set<String> basenames, Set<String> extensions
    ) {
        var hits = new ArrayList<String>();
        Matcher m = BACKTICKED_PATH.matcher(line);
        while (m.find()) {
            String ref = m.group(1);
            // Only judge tokens whose extension we recognize as a real file
            // type; anything else is left untouched (e.g. `e.g.`, `v1.2`).
            if (!extensions.contains(m.group(2).toLowerCase())) continue;
            String basename = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
            if (sourceSet.contains(ref) || basenames.contains(basename)) continue;
            if (SPRING_PROFILE_CONFIG.matcher(basename).matches()) continue;
            hits.add(ref);
        }
        return hits;
    }
}
