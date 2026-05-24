package com.acltabontabon.launchpad.scanner.doc;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Pulls the first useful prose paragraph from a README's content. Used as the
 * highest-priority source for the `## What this project is` section in the
 * generated primary file - a good README opener beats anything a 7B local
 * model can synthesise.
 * <p>
 * Skips: heading lines, badge / image lines, bullet lists, code fences,
 * front-matter blocks. Stops at the next heading.
 * Returns an empty string when no qualifying paragraph is found.
 */
public final class ReadmeIntroExtractor {

    private static final int MAX_CHARS = 1_500;
    private static final int MAX_PARAGRAPHS = 2;
    private static final Pattern BADGE_OR_IMAGE = Pattern.compile("^\\s*(?:!?\\[[^\\]]*]\\([^)]*\\)\\s*)+$");

    private ReadmeIntroExtractor() {}

    public static String extract(String readme) {
        if (readme == null || readme.isBlank()) return "";

        var lines = readme.split("\n", -1);
        int i = skipFrontMatter(lines, 0);
        boolean seenTitle = false;
        boolean inFence = false;
        var paragraphs = new ArrayList<String>();
        var current = new ArrayList<String>();

        for (; i < lines.length; i++) {
            var raw = lines[i];
            var line = raw.strip();

            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;

            if (line.startsWith("#")) {
                if (!seenTitle) {
                    seenTitle = true;
                    continue;
                }
                // Next heading after the title ends the intro region.
                break;
            }

            if (line.isEmpty()) {
                if (!current.isEmpty()) {
                    paragraphs.add(String.join(" ", current).strip());
                    current = new ArrayList<>();
                    if (paragraphs.size() >= MAX_PARAGRAPHS) break;
                    if (totalChars(paragraphs) >= MAX_CHARS) break;
                }
                continue;
            }

            if (isBadgeOrImageLine(line) || isBulletLine(line) || isHorizontalRule(line)) continue;

            // Treat blockquotes as paragraph continuation - they often carry
            // the punchline that the previous prose line set up (e.g. a
            // colon-terminated lead followed by `> Question?`).
            if (line.startsWith(">")) {
                current.add(line.substring(1).strip());
            } else {
                current.add(line);
            }

            if (totalChars(paragraphs) + String.join(" ", current).length() >= MAX_CHARS) break;
        }
        if (!current.isEmpty()) paragraphs.add(String.join(" ", current).strip());

        var result = String.join("\n\n", paragraphs).strip();
        if (result.length() > MAX_CHARS) result = result.substring(0, MAX_CHARS).stripTrailing();
        return result;
    }

    private static int totalChars(java.util.List<String> paragraphs) {
        int total = 0;
        for (var p : paragraphs) total += p.length() + 2;
        return total;
    }

    private static int skipFrontMatter(String[] lines, int from) {
        if (from >= lines.length) return from;
        if (!"---".equals(lines[from].strip())) return from;
        for (int i = from + 1; i < lines.length; i++) {
            if ("---".equals(lines[i].strip())) return i + 1;
        }
        return from;
    }

    private static boolean isBadgeOrImageLine(String line) {
        return BADGE_OR_IMAGE.matcher(line).matches();
    }

    private static boolean isBulletLine(String line) {
        if (line.length() < 2) return false;
        var c = line.charAt(0);
        if ((c == '-' || c == '*' || c == '+') && line.charAt(1) == ' ') return true;
        // Numeric bullets: "1. ", "12. "
        int idx = 0;
        while (idx < line.length() && Character.isDigit(line.charAt(idx))) idx++;
        return idx > 0 && idx + 1 < line.length() && line.charAt(idx) == '.' && line.charAt(idx + 1) == ' ';
    }

    private static boolean isHorizontalRule(String line) {
        if (line.length() < 3) return false;
        var c = line.charAt(0);
        if (c != '-' && c != '*' && c != '_') return false;
        for (int i = 1; i < line.length(); i++) {
            if (line.charAt(i) != c && line.charAt(i) != ' ') return false;
        }
        return true;
    }

}
