package com.acltabontabon.launchpad.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groups scanned source files into top-level packages and extracts a sample
 * of public symbols per group. The model gets "users/ has 12 files exporting
 * UserService, UserController, ..." instead of a flat list of 2000 paths.
 */
public final class StructureSummarizer {

    private static final int MAX_SYMBOLS_PER_PACKAGE = 8;
    private static final int MAX_FILES_INSPECTED_PER_PACKAGE = 30;
    private static final int MAX_PACKAGES = 40;
    private static final int GROUPING_DEPTH = 3;   // group by first N path segments

    // Public-symbol patterns - cheap regex, not real parsers. Captures most cases
    // for the languages launchpad targets (Java, Kotlin, TS/JS, Python, Go, Rust).
    private static final Pattern JAVA_CLASS = Pattern.compile(
        "^\\s*public\\s+(?:abstract\\s+|final\\s+|sealed\\s+)?(?:class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern KOTLIN_CLASS = Pattern.compile(
        "^\\s*(?:public\\s+|open\\s+|abstract\\s+|sealed\\s+|data\\s+)*(?:class|interface|object)\\s+(\\w+)");
    private static final Pattern PYTHON_DEF = Pattern.compile(
        "^(?:class|def)\\s+([A-Za-z_]\\w*)");
    private static final Pattern TS_EXPORT = Pattern.compile(
        "^\\s*export\\s+(?:default\\s+)?(?:async\\s+)?(?:class|function|interface|type|const|enum)\\s+(\\w+)");
    private static final Pattern GO_DECL = Pattern.compile(
        "^(?:func|type)\\s+(?:\\([^)]*\\)\\s+)?([A-Z]\\w*)");
    private static final Pattern RUST_PUB = Pattern.compile(
        "^\\s*pub(?:\\([^)]*\\))?\\s+(?:async\\s+)?(?:fn|struct|enum|trait|mod)\\s+(\\w+)");

    public List<PackageSummary> summarize(Path root, List<String> sourceFiles) {
        // Group by first GROUPING_DEPTH path segments.
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String rel : sourceFiles) {
            grouped.computeIfAbsent(groupKey(rel), k -> new ArrayList<>()).add(rel);
        }
        // Pick the largest groups (where the action is).
        var sorted = grouped.entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<String, List<String>> e) -> e.getValue().size()).reversed())
            .limit(MAX_PACKAGES)
            .toList();

        var out = new ArrayList<PackageSummary>();
        for (var entry : sorted) {
            var files = entry.getValue();
            var symbols = sampleSymbols(root, files);
            out.add(new PackageSummary(entry.getKey(), files.size(), symbols));
        }
        return out;
    }

    private static String groupKey(String relativePath) {
        var parts = relativePath.split("[/\\\\]");
        if (parts.length <= 1) return "(root)";
        int depth = Math.min(parts.length - 1, GROUPING_DEPTH);
        return String.join("/", java.util.Arrays.copyOfRange(parts, 0, depth));
    }

    private List<String> sampleSymbols(Path root, List<String> files) {
        var seen = new java.util.LinkedHashSet<String>();
        int inspected = 0;
        for (String rel : files) {
            if (inspected >= MAX_FILES_INSPECTED_PER_PACKAGE) break;
            if (seen.size() >= MAX_SYMBOLS_PER_PACKAGE) break;
            inspected++;
            var file = root.resolve(rel);
            try {
                for (String line : Files.readAllLines(file)) {
                    if (seen.size() >= MAX_SYMBOLS_PER_PACKAGE) break;
                    String sym = extractSymbol(line, rel);
                    if (sym != null) seen.add(sym);
                }
            } catch (IOException ignored) {
            }
        }
        return new ArrayList<>(seen);
    }

    private static String extractSymbol(String line, String filename) {
        var lower = filename.toLowerCase();
        Matcher m;
        if (lower.endsWith(".java")) {
            m = JAVA_CLASS.matcher(line);
            return m.find() ? m.group(1) : null;
        }
        if (lower.endsWith(".kt")) {
            m = KOTLIN_CLASS.matcher(line);
            return m.find() ? m.group(1) : null;
        }
        if (lower.endsWith(".py")) {
            m = PYTHON_DEF.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                return name.startsWith("_") ? null : name;
            }
            return null;
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")
            || lower.endsWith(".js") || lower.endsWith(".jsx")) {
            m = TS_EXPORT.matcher(line);
            return m.find() ? m.group(1) : null;
        }
        if (lower.endsWith(".go")) {
            m = GO_DECL.matcher(line);
            return m.find() ? m.group(1) : null;
        }
        if (lower.endsWith(".rs")) {
            m = RUST_PUB.matcher(line);
            return m.find() ? m.group(1) : null;
        }
        return null;
    }
}
