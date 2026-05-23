package com.acltabontabon.launchpad.tui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class PathAutocomplete {

    /** Hard cap on the list panel to keep rendering predictable in huge dirs. */
    private static final int MAX_MATCHES = 20;

    private PathAutocomplete() {}

    /** Ghost-text suffix for the keyboard-only Tab-completion flow. */
    public static String suggest(String input) {
        return matches(input).stream()
            .min(Comparator.comparing(String::toLowerCase))
            .map(name -> {
                var prefix = prefixOf(input);
                return name.substring(prefix.length());
            })
            .orElse("");
    }

    /**
     * All matching sibling directory names (case-sensitive prefix match on the
     * unfinished trailing segment). Capped at {@link #MAX_MATCHES} and sorted
     * case-insensitively so the list panel order is stable.
     */
    public static List<String> matches(String input) {
        if (input == null || input.isEmpty()) return List.of();
        var parent = parentOf(input);
        var prefix = prefixOf(input);
        if (parent == null || !Files.isDirectory(parent)) return List.of();
        try (Stream<Path> entries = Files.list(parent)) {
            return entries
                .filter(Files::isDirectory)
                .map(entry -> entry.getFileName().toString())
                .filter(name -> name.startsWith(prefix))
                .sorted(Comparator.comparing(String::toLowerCase))
                .limit(MAX_MATCHES)
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path parentOf(String input) {
        if (input.endsWith("/")) return Path.of(input);
        var p = Path.of(input);
        return p.getParent();
    }

    private static String prefixOf(String input) {
        if (input.endsWith("/")) return "";
        var p = Path.of(input);
        return p.getFileName() == null ? "" : p.getFileName().toString();
    }
}
