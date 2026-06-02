package com.acltabontabon.launchpad.task;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure leaf text utilities shared across task collaborators: significant-word
 * extraction, stem-tolerant set overlap, first-sentence pick. No domain types,
 * no state. Kept in one place so the duplicate-detection and grounding helpers
 * agree on the same notion of "significant word".
 */
public final class TaskTextSupport {

    private TaskTextSupport() {}

    /** Stopwords stripped by {@link #significantWords}. Tuned for English
     *  interview-question text - common pronouns, articles, modals. */
    static final Set<String> STOPWORDS = Set.of(
        "a", "an", "the", "is", "are", "be", "should", "would", "could", "this",
        "that", "for", "of", "to", "or", "and", "in", "on", "at", "with", "use",
        "using", "have", "has", "do", "does", "did", "will", "your", "you", "any",
        "new", "what", "which", "how", "why", "when", "where", "it", "its", "by",
        "from", "as", "if", "then", "than", "so", "but", "not", "no", "yes"
    );

    /** Lowercased, non-alphanumeric-stripped words longer than 2 chars, minus
     *  {@link #STOPWORDS}. Returns an empty set for null or no-content input. */
    public static Set<String> significantWords(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
            .filter(w -> !w.isBlank() && w.length() > 2 && !STOPWORDS.contains(w))
            .collect(Collectors.toSet());
    }

    /** Count of words in {@code a} that match any in {@code b}, with stem
     *  tolerance via {@link #wordMatches}. */
    public static int stemTolerantOverlap(Set<String> a, Set<String> b) {
        int matches = 0;
        for (var x : a) {
            for (var y : b) {
                if (wordMatches(x, y)) { matches++; break; }
            }
        }
        return matches;
    }

    /** Exact-equal, or a 4+ char prefix-overlap (tolerates plurals / -ed / -ing). */
    public static boolean wordMatches(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() >= 4 && b.length() >= 4) {
            if (b.startsWith(a)) return true;
            if (a.startsWith(b)) return true;
        }
        return false;
    }

    /**
     * First sentence of the input, with newlines collapsed to spaces. A sentence
     * ends at ". " ONLY when the next non-space character is a capital letter -
     * this avoids splitting at common abbreviations like "e.g.", "i.e.", "vs.",
     * "etc." where the next character is lowercase, a backtick, or punctuation.
     * Falls back to the first line capped at 240 chars if no real boundary exists.
     */
    public static String firstSentence(String text) {
        if (text == null) return "";
        var collapsed = text.strip().replaceAll("\\R+", " ").replaceAll(" +", " ");
        if (collapsed.isEmpty()) return "";
        var matcher = java.util.regex.Pattern.compile("\\. (?=[A-Z])").matcher(collapsed);
        if (matcher.find()) return collapsed.substring(0, matcher.start() + 1);
        if (collapsed.endsWith(".")) return collapsed;
        return collapsed.length() > 240 ? collapsed.substring(0, 240) + "..." : collapsed;
    }
}
