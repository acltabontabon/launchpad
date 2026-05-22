package com.acltabontabon.launchpad.scanner;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal .gitignore matcher. Supports the common cases: comments, blank lines,
 * basename-only patterns, root-anchored patterns, directory-only patterns
 * (trailing /), and glob wildcards (*, ?, **).
 * <p>
 * Negation (! prefix) is intentionally not supported - rare in scanned projects
 * and the false-positive cost (one extra file scanned) is trivial.
 */
public final class GitIgnoreFilter {

    private final List<Entry> entries;
    private final boolean enabled;

    private GitIgnoreFilter(List<Entry> entries, boolean enabled) {
        this.entries = entries;
        this.enabled = enabled;
    }

    public static GitIgnoreFilter empty() {
        return new GitIgnoreFilter(List.of(), false);
    }

    public static GitIgnoreFilter forRoot(Path root) {
        var gitignore = root.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) return empty();
        var entries = new ArrayList<Entry>();
        try {
            for (var raw : Files.readAllLines(gitignore)) {
                var line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
                entries.add(compile(line));
            }
        } catch (IOException e) {
            return empty();
        }
        return new GitIgnoreFilter(entries, !entries.isEmpty());
    }

    /** @return true if the given relative path (or any parent dir) is ignored. */
    public boolean isIgnored(Path relativePath, boolean isDirectory) {
        if (!enabled) return false;
        for (var entry : entries) {
            if (entry.matches(relativePath, isDirectory)) return true;
        }
        return false;
    }

    private static Entry compile(String pattern) {
        boolean dirOnly = pattern.endsWith("/");
        if (dirOnly) pattern = pattern.substring(0, pattern.length() - 1);
        boolean rooted = pattern.startsWith("/");
        if (rooted) pattern = pattern.substring(1);

        // No slash in the (now-stripped) pattern means: match basename anywhere in tree.
        boolean basenameOnly = !pattern.contains("/");

        String glob = basenameOnly ? pattern : (rooted ? pattern : "**/" + pattern);
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        return new Entry(matcher, dirOnly, basenameOnly);
    }

    private record Entry(PathMatcher matcher, boolean dirOnly, boolean basenameOnly) {
        boolean matches(Path relativePath, boolean isDirectory) {
            if (dirOnly && !isDirectory) return false;
            if (basenameOnly) {
                var name = relativePath.getFileName();
                return name != null && matcher.matches(name);
            }
            return matcher.matches(relativePath);
        }
    }
}
