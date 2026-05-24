package com.acltabontabon.launchpad.scanner.doc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts named sections from a README in addition to the intro paragraph
 * already pulled by {@link ReadmeIntroExtractor}. Available on
 * {@link ProjectContext} for any consumer that needs the project's actual
 * Quick start / Usage / Commands instructions.
 * <p>
 * Section matching is case-insensitive and matches the heading text against
 * a small allowlist. Each section is capped at {@link #MAX_SECTION_LINES}
 * lines so the evidence packet stays bounded.
 */
public final class ReadmeSectionsExtractor {

    private static final int MAX_SECTION_LINES = 30;

    private static final List<String> SECTION_NAMES = List.of(
        "quick start", "quickstart", "usage", "commands",
        "run", "build", "getting started", "how to use", "scripts"
    );

    private ReadmeSectionsExtractor() {}

    /**
     * Returns a map of canonical section name (lowercased) → section body.
     * Empty when no recognised sections are present.
     */
    public static Map<String, String> extract(String readme) {
        if (readme == null || readme.isBlank()) return Map.of();
        var out = new LinkedHashMap<String, String>();
        var lines = readme.split("\n", -1);

        String currentSection = null;
        var currentBody = new StringBuilder();
        int linesCaptured = 0;

        for (var raw : lines) {
            var line = raw;
            var stripped = line.strip();

            if (stripped.startsWith("#")) {
                // Heading -> flush current, decide if next heading is recognised.
                if (currentSection != null) {
                    out.put(currentSection, currentBody.toString().stripTrailing());
                }
                currentSection = matchSectionName(stripped);
                currentBody.setLength(0);
                linesCaptured = 0;
                continue;
            }

            if (currentSection == null) continue;
            if (linesCaptured >= MAX_SECTION_LINES) continue;

            currentBody.append(line).append('\n');
            linesCaptured++;
        }
        if (currentSection != null) {
            out.put(currentSection, currentBody.toString().stripTrailing());
        }
        return out;
    }

    private static String matchSectionName(String headingLine) {
        // Strip leading `#` chars and lowercase the heading text.
        int i = 0;
        while (i < headingLine.length() && headingLine.charAt(i) == '#') i++;
        var text = headingLine.substring(i).strip().toLowerCase(Locale.ROOT);
        for (var name : SECTION_NAMES) {
            if (text.equals(name) || text.startsWith(name + " ") || text.startsWith(name + ":")) {
                return name;
            }
        }
        return null;
    }
}
