package com.acltabontabon.launchpad.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Catalogs executable scripts the project ships - things like
 * {@code scripts/pgo-build.sh} or {@code Makefile} targets. Surfaced on
 * {@link ProjectContext} for any consumer that wants a quick list of
 * project-specific actions beyond the deterministic Commands block.
 * <p>
 * Looks in {@code scripts/}, {@code bin/}, and the project root for
 * {@code Makefile}. Returns the script name (or target name for Makefile)
 * mapped to its first descriptive line - usually the leading comment or the
 * {@code ## target:} doc convention. Bounded at {@link #MAX_ENTRIES}.
 */
public final class ProjectScriptsProvider {

    private static final int MAX_ENTRIES = 8;
    private static final long MAX_FILE_BYTES = 64 * 1024;
    private static final List<String> SCRIPT_DIRS = List.of("scripts", "bin");

    private ProjectScriptsProvider() {}

    /**
     * Returns a map of script identifier → short description. Empty when the
     * project ships no scripts. Never throws.
     */
    public static Map<String, String> catalog(Path projectRoot) {
        var out = new LinkedHashMap<String, String>();
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return out;

        for (var dirName : SCRIPT_DIRS) {
            if (out.size() >= MAX_ENTRIES) break;
            var dir = projectRoot.resolve(dirName);
            if (!Files.isDirectory(dir)) continue;
            collectScriptsIn(dir, out);
        }

        if (out.size() < MAX_ENTRIES) {
            var makefile = projectRoot.resolve("Makefile");
            if (Files.isRegularFile(makefile)) collectMakefileTargets(makefile, out);
        }
        return out;
    }

    private static void collectScriptsIn(Path dir, LinkedHashMap<String, String> out) {
        try (Stream<Path> entries = Files.list(dir)) {
            entries.sorted()
                .filter(Files::isRegularFile)
                .filter(p -> isExecutableScript(p.getFileName().toString()))
                .limit(MAX_ENTRIES - (long) out.size())
                .forEach(p -> {
                    var desc = firstCommentLine(p);
                    var key = dir.getFileName().toString() + "/" + p.getFileName();
                    out.put(key, desc);
                });
        } catch (IOException ignored) {
            // best-effort; an unreadable dir is silent
        }
    }

    private static boolean isExecutableScript(String name) {
        var lower = name.toLowerCase();
        return lower.endsWith(".sh") || lower.endsWith(".bash")
            || lower.endsWith(".py") || lower.endsWith(".rb")
            || lower.endsWith(".ps1");
    }

    private static String firstCommentLine(Path script) {
        try {
            if (Files.size(script) > MAX_FILE_BYTES) return "";
            for (var raw : Files.readAllLines(script)) {
                var line = raw.strip();
                if (line.isEmpty()) continue;
                if (line.startsWith("#!")) continue; // shebang
                if (line.startsWith("#")) {
                    return line.replaceFirst("^#+\\s*", "").strip();
                }
                if (line.startsWith("//")) {
                    return line.replaceFirst("^//+\\s*", "").strip();
                }
                return ""; // first non-empty line isn't a comment
            }
        } catch (IOException ignored) {
            // skip
        }
        return "";
    }

    private static void collectMakefileTargets(Path makefile, LinkedHashMap<String, String> out) {
        try {
            if (Files.size(makefile) > MAX_FILE_BYTES) return;
            String pendingDoc = "";
            for (var raw : Files.readAllLines(makefile)) {
                if (out.size() >= MAX_ENTRIES) return;
                var line = raw;
                var stripped = line.strip();
                if (stripped.startsWith("##")) {
                    pendingDoc = stripped.replaceFirst("^##+\\s*", "").strip();
                    continue;
                }
                if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("\t")) continue;
                int colon = stripped.indexOf(':');
                if (colon <= 0) continue;
                var target = stripped.substring(0, colon).strip();
                if (target.isEmpty() || target.equals(".PHONY") || target.contains(" ")) {
                    pendingDoc = "";
                    continue;
                }
                out.put("make " + target, pendingDoc);
                pendingDoc = "";
            }
        } catch (IOException ignored) {
            // skip
        }
    }
}
