package com.acltabontabon.launchpad.scanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pulls one representative source file per top-level package detected by
 * {@link StructureSummarizer}. The chosen file is the one whose name matches
 * the first sample symbol (or, failing that, the alphabetically-first source
 * file under the package). The first {@link #MAX_LINES_PER_FILE} lines are
 * returned so the project-map synthesis job can ground its bullets in actual
 * source instead of inventing module structures.
 */
public final class PackageRepresentativeSource {

    private static final int MAX_PACKAGES = 5;
    private static final int MAX_LINES_PER_FILE = 60;
    private static final int MAX_TOTAL_CHARS = 8 * 1024;
    private static final long MAX_FILE_BYTES = 24 * 1024;

    private PackageRepresentativeSource() {}

    /**
     * Returns a map of package-path → first-N-lines snippet. Empty when no
     * package has a readable representative file or the total budget is
     * exhausted.
     */
    public static Map<String, String> collect(Path projectRoot,
                                              List<PackageSummary> packages,
                                              List<String> sourceFiles) {
        var out = new LinkedHashMap<String, String>();
        if (projectRoot == null || packages == null || packages.isEmpty() || sourceFiles == null) return out;

        int spent = 0;
        int picked = 0;
        for (var pkg : packages) {
            if (picked >= MAX_PACKAGES) break;
            if (spent >= MAX_TOTAL_CHARS) break;
            var file = pickRepresentativeFile(pkg, sourceFiles);
            if (file == null) continue;
            var snippet = readBounded(projectRoot.resolve(file));
            if (snippet == null || snippet.isBlank()) continue;
            int remaining = MAX_TOTAL_CHARS - spent;
            if (snippet.length() > remaining) {
                snippet = snippet.substring(0, Math.max(0, remaining - 32)) + "\n... (truncated)";
            }
            out.put(pkg.path(), snippet);
            spent += snippet.length();
            picked++;
        }
        return out;
    }

    private static String pickRepresentativeFile(PackageSummary pkg, List<String> sourceFiles) {
        var prefix = pkg.path().endsWith("/") ? pkg.path() : pkg.path() + "/";

        // Prefer a file whose name matches the first sample symbol.
        if (!pkg.sampleSymbols().isEmpty()) {
            var firstSymbol = pkg.sampleSymbols().get(0);
            for (var rel : sourceFiles) {
                if (!rel.startsWith(prefix)) continue;
                var name = rel.substring(rel.lastIndexOf('/') + 1);
                var stem = stripExt(name);
                if (stem.equalsIgnoreCase(firstSymbol)) return rel;
            }
        }
        // Otherwise return the alphabetically-first JVM source under the package.
        return sourceFiles.stream()
            .filter(p -> p.startsWith(prefix))
            .filter(p -> isJvmSource(p))
            .sorted()
            .findFirst()
            .orElse(null);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static boolean isJvmSource(String relative) {
        var lower = relative.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt");
    }

    private static String readBounded(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            if (Files.size(file) > MAX_FILE_BYTES) return null;
            var content = Files.readString(file);
            return firstLines(content, MAX_LINES_PER_FILE);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLines(String content, int maxLines) {
        var lines = content.split("\n", -1);
        if (lines.length <= maxLines) return content;
        var sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]);
            if (i < maxLines - 1) sb.append('\n');
        }
        sb.append("\n... (truncated)");
        return sb.toString();
    }
}
