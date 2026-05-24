package com.acltabontabon.launchpad.scanner.doc;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides a {@link Purpose} for a documentation page from its project-relative
 * path. Deterministic by design - filename stem and path segments are matched
 * against a small, ordered rule table. When no rule fires the result is
 * {@link Purpose#UNKNOWN}; callers that wired in an {@link AiPurposeClassifier}
 * may promote {@code UNKNOWN} to a named bucket by asking the local model.
 * <p>
 * The classifier is pure and stateless - no IO, no Spring dependencies. It is
 * constructed by the scanner with the AI fallback wired in (or null, in
 * tests).
 */
public final class PurposeClassifier {

    /**
     * Ordered rule table. The first {@link Purpose} whose token list shares
     * any normalised token with the filename stem or any path segment wins.
     * Order matters: e.g. {@code release-notes.md} contains both "release"
     * (CHANGELOG) and arguably "notes" - keeping CHANGELOG near the top
     * lets it claim the file unambiguously.
     */
    private static final List<Map.Entry<Purpose, List<String>>> RULES = List.of(
        Map.entry(Purpose.CHANGELOG, List.of(
            "changelog", "history", "releases", "release-notes", "releasenotes")),
        Map.entry(Purpose.CONTRIBUTION, List.of(
            "contributing", "contribute", "development", "dev-guide", "devguide")),
        Map.entry(Purpose.SETUP, List.of(
            "install", "installation", "setup", "getting-started", "getting_started",
            "quickstart", "quick-start")),
        Map.entry(Purpose.ARCHITECTURE, List.of(
            "architecture", "design", "adr", "adrs", "hld", "lld")),
        Map.entry(Purpose.API, List.of(
            "api", "reference", "openapi", "swagger", "endpoints")),
        Map.entry(Purpose.OPERATIONS, List.of(
            "operations", "ops", "runbook", "run-book", "deploy", "deployment",
            "monitoring", "observability")),
        Map.entry(Purpose.OVERVIEW, List.of(
            "readme", "overview", "intro", "introduction", "about", "index"))
    );

    private final AiPurposeClassifier aiFallback;

    public PurposeClassifier(AiPurposeClassifier aiFallback) {
        this.aiFallback = aiFallback;
    }

    /** Deterministic-only classifier; useful for tests and CLI runs without Spring AI wired in. */
    public static PurposeClassifier deterministicOnly() {
        return new PurposeClassifier(null);
    }

    /**
     * Classify a page. {@code relativePath} is the project-relative path of
     * the doc file. {@code contentSupplier} is consulted only when the
     * heuristics fail AND the AI fallback is enabled - it returns the file
     * content as text, or {@code null}/empty if unreadable.
     */
    public Purpose classify(String relativePath, java.util.function.Supplier<String> contentSupplier) {
        Purpose deterministic = classifyByPath(relativePath);
        if (deterministic != Purpose.UNKNOWN) return deterministic;
        if (aiFallback == null || !aiFallback.isEnabled()) return Purpose.UNKNOWN;
        String content = contentSupplier == null ? null : contentSupplier.get();
        return aiFallback.classify(relativePath, content);
    }

    /** Deterministic-only entry point. Useful in tests. */
    public Purpose classifyByPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return Purpose.UNKNOWN;
        String lower = relativePath.toLowerCase(Locale.ROOT);
        // Split on path separators and the inner filename punctuation we treat
        // as boundaries, so {@code docs/getting-started.md} contributes
        // ["docs", "getting-started", "getting", "started", "md"].
        var tokens = new java.util.LinkedHashSet<String>();
        for (String segment : lower.split("[/\\\\]")) {
            if (segment.isEmpty()) continue;
            tokens.add(stripExtension(segment));
            tokens.add(segment);
            for (String inner : segment.split("[-_.\\s]+")) {
                if (!inner.isEmpty()) tokens.add(inner);
            }
        }
        for (var rule : RULES) {
            for (String needle : rule.getValue()) {
                if (tokens.contains(needle)) return rule.getKey();
            }
        }
        return Purpose.UNKNOWN;
    }

    private static String stripExtension(String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }
}
