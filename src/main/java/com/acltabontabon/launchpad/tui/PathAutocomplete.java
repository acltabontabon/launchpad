package com.acltabontabon.launchpad.tui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public final class PathAutocomplete {

    private PathAutocomplete() {}

    public static String suggest(String input) {
        if (input == null || input.isEmpty()) return "";

        Path parent;
        String prefix;
        if (input.endsWith("/")) {
            parent = Path.of(input);
            prefix = "";
        } else {
            var p = Path.of(input);
            parent = p.getParent();
            prefix = p.getFileName() == null ? "" : p.getFileName().toString();
        }

        if (parent == null || !Files.isDirectory(parent)) return "";

        try (Stream<Path> entries = Files.list(parent)) {
            return entries
                .filter(Files::isDirectory)
                .map(entry -> entry.getFileName().toString())
                .filter(name -> name.startsWith(prefix))
                .min(Comparator.comparing(String::toLowerCase))
                .map(name -> name.substring(prefix.length()))
                .orElse("");
        } catch (IOException e) {
            return "";
        }
    }
}
