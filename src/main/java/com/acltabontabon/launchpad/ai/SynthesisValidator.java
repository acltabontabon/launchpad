package com.acltabontabon.launchpad.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared rejection rules for body fragments synthesised by the local model.
 * The fragments are assembled into a deterministic AGENTS.md skeleton by
 * {@code ContextTemplateEngine}; anything the validator rejects falls back
 * to the deterministic body the caller supplied.
 * <p>
 * Validation is intentionally strict: a small local model will, given the
 * chance, parrot the prompt, emit raw XML / file dumps, invent headings, or
 * lead with "Here is..." style preamble. We catch all of that here so the
 * primary file never re-shows those failures to the user.
 */
public final class SynthesisValidator {

    /** Three output shapes are accepted; everything else is filler. */
    public enum Shape {
        /** Plain prose: no headings, no bullets, no code fences. */
        PROSE,
        /** Short bullet list: every non-blank line starts with `-` or `*`. */
        BULLETS,
        /**
         * Fixed-format key-value lines: every non-blank line matches
         * "{@code KEY => VALUE}". The {@code allowedTokens} set, when
         * non-empty, must contain every key (exact match, case-insensitive);
         * any line whose key is out-of-set fails the fragment. Used by the
         * endpoint-notes synthesis so each output line maps cleanly to a
         * known endpoint.
         */
        LINES
    }

    private static final int LINES_MAX_VALUE_CHARS = 160;
    private static final java.util.regex.Pattern LINE_KV = java.util.regex.Pattern.compile(
        "^([^=]{2,80}?)\\s*=>\\s*(.*)$");
    /**
     * Bare handler reference like {@code HelloController.hello} - the most common
     * parroting failure for endpoint-note synthesis. Reject so the table cell
     * stays empty instead of duplicating the architecture tree's content.
     */
    private static final java.util.regex.Pattern BARE_HANDLER = java.util.regex.Pattern.compile(
        "^[A-Z]\\w+\\.\\w+$");

    private static final List<Pattern> FORBIDDEN_TOKENS = List.of(
        Pattern.compile("^#{1,6}\\s", Pattern.MULTILINE),                    // markdown heading on its own line
        Pattern.compile("```"),                                                // code fence
        Pattern.compile("<(?:project|dependencies|plugin|profile|build|configuration|properties)\\b",
            Pattern.CASE_INSENSITIVE),                                          // raw XML tags from pom.xml
        Pattern.compile("\\b(?:pom\\.xml|README\\.md)\\b"),                   // raw filename mentions
        Pattern.compile("###\\s*##"),                                          // malformed stacked heading
        Pattern.compile("\\{\\{\\s*\\w+\\s*}}"),                               // unresolved prompt placeholder
        // Double-bullet: the model wrapped an input "- foo" line in backticks
        // and prefixed its own "- ", producing "- `- foo` ...". A clear smell
        // of "model parroted the input shape" rather than synthesising prose.
        Pattern.compile("^[-*]\\s+`?-\\s", Pattern.MULTILINE)
    );

    private static final List<Pattern> INSTRUCTION_LEAKS = List.of(
        Pattern.compile("^(here(?:'s| is| are)\\b)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(sure[,!.]?\\b)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(i (?:will|can|would|'ll)\\b)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(based on (?:the )?input)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(certainly[,!.]?\\b)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(let me\\b)", Pattern.CASE_INSENSITIVE)
    );

    /** Backwards-compatible overload. No allowlist check is applied. */
    public String reject(String fragment, Shape shape, int maxOutputChars) {
        return reject(fragment, shape, maxOutputChars, java.util.Set.of());
    }

    /**
     * Returns the first rejection reason, or {@code null} when the fragment
     * is accepted. Rejection reasons are short, lowercase phrases suitable
     * for debug logging.
     *
     * @param allowedTokens  When non-empty, every backticked / path-like
     *                       reference in the fragment must match one of
     *                       these tokens (case-insensitive substring).
     *                       Any out-of-set reference fails the fragment.
     *                       Empty set disables the check.
     */
    public String reject(String fragment, Shape shape, int maxOutputChars, java.util.Set<String> allowedTokens) {
        if (fragment == null) return "null output";
        var trimmed = fragment.strip();
        if (trimmed.isEmpty()) return "empty output";
        if (trimmed.length() > maxOutputChars) return "exceeds max-output-chars (" + trimmed.length() + ")";
        if (looksRepetitive(trimmed)) return "excessive repeated lines";

        for (var p : FORBIDDEN_TOKENS) {
            if (p.matcher(trimmed).find()) return "forbidden token: " + p.pattern();
        }
        var firstLine = trimmed.lines().findFirst().orElse("").strip();
        for (var p : INSTRUCTION_LEAKS) {
            if (p.matcher(firstLine).find()) return "instruction leakage";
        }

        if (shape == Shape.PROSE && containsBulletLine(trimmed)) return "prose shape contains bullets";
        if (shape == Shape.BULLETS && !isBulletList(trimmed)) return "bullets shape is not a bullet list";
        if (shape == Shape.LINES) {
            var linesReject = rejectLinesShape(trimmed, allowedTokens);
            if (linesReject != null) return linesReject;
            // LINES shape does its own per-key allowlist enforcement; skip the
            // generic substring check so backticked tokens inside the value
            // (e.g. `LoanApplication`) don't trip the broader rule.
            return null;
        }

        if (allowedTokens != null && !allowedTokens.isEmpty()) {
            var offender = findOutOfAllowlistReference(trimmed, allowedTokens);
            if (offender != null) return "out-of-allowlist reference: " + offender;
        }

        return null;
    }

    /**
     * Validates a fragment in {@link Shape#LINES} shape: every non-blank line
     * must match {@code KEY => VALUE}; values are length-capped; keys must
     * appear in {@code allowedTokens} (exact, case-insensitive). Returns the
     * first rejection reason, or {@code null} when every line passes.
     */
    private static String rejectLinesShape(String fragment, java.util.Set<String> allowedTokens) {
        var allowedLower = new java.util.HashSet<String>();
        if (allowedTokens != null) {
            for (var t : allowedTokens) {
                if (t == null || t.isBlank()) continue;
                allowedLower.add(t.strip().toLowerCase(java.util.Locale.ROOT));
            }
        }
        boolean anyLine = false;
        for (var raw : fragment.split("\n")) {
            var line = raw.strip();
            if (line.isEmpty()) continue;
            anyLine = true;
            var m = LINE_KV.matcher(line);
            if (!m.matches()) return "lines shape: missing `=>` separator on line: " + line;
            var value = m.group(2).strip();
            if (value.length() > LINES_MAX_VALUE_CHARS) {
                return "lines shape: value > " + LINES_MAX_VALUE_CHARS + " chars";
            }
            if (BARE_HANDLER.matcher(value).matches()) {
                return "lines shape: value is a bare handler reference: " + value;
            }
            if (!allowedLower.isEmpty()) {
                var key = m.group(1).strip().toLowerCase(java.util.Locale.ROOT);
                if (!allowedLower.contains(key)) {
                    return "lines shape: unknown key " + m.group(1).strip();
                }
            }
        }
        return anyLine ? null : "lines shape: no lines";
    }

    public boolean accept(String fragment, Shape shape, int maxOutputChars) {
        return reject(fragment, shape, maxOutputChars) == null;
    }

    private static boolean containsBulletLine(String fragment) {
        return fragment.lines().map(String::strip)
            .anyMatch(l -> l.startsWith("- ") || l.startsWith("* ") || l.startsWith("+ "));
    }

    private static boolean isBulletList(String fragment) {
        var lines = fragment.lines()
            .map(String::strip)
            .filter(l -> !l.isEmpty())
            .toList();
        if (lines.isEmpty()) return false;
        for (var l : lines) {
            if (!(l.startsWith("- ") || l.startsWith("* ") || l.startsWith("+ "))) return false;
        }
        return lines.size() <= 10;
    }

    private static final Pattern BACKTICKED_REF = Pattern.compile("`([^`\\n]+)`");
    /**
     * Path-like word outside backticks - "service/", "controller/foo", "pgo-instrument".
     * Strict on the boundary characters: starts at a word boundary, contains
     * a `/` or a `-` connecting word chars, ends at a word boundary or
     * after a trailing slash. Matches the kind of fabricated package names
     * we have seen ("service/", "domain/") without flagging ordinary prose.
     */
    private static final Pattern PATHISH_WORD = Pattern.compile(
        "\\b([A-Za-z][A-Za-z0-9_-]*(?:/[A-Za-z0-9_./-]*)+|[A-Za-z][A-Za-z0-9_-]*/)");

    /**
     * Returns the first identifier-like reference in {@code fragment} that
     * is NOT a case-insensitive substring of any token in
     * {@code allowedTokens}, or {@code null} when every reference is
     * accounted for.
     * <p>
     * The check is intentionally permissive: "controller" in the allowlist
     * accepts the model writing `LoanDecisionController` or `controller/`.
     * The goal is to catch wholly fabricated names ("service/" when the
     * project never had one) without rejecting reasonable paraphrases.
     */
    static String findOutOfAllowlistReference(String fragment, java.util.Set<String> allowedTokens) {
        var lowered = new java.util.ArrayList<String>();
        for (var t : allowedTokens) {
            if (t == null) continue;
            var s = t.strip();
            if (!s.isEmpty()) lowered.add(s.toLowerCase(java.util.Locale.ROOT));
        }
        if (lowered.isEmpty()) return null;

        java.util.function.Predicate<String> covered = ref -> {
            var refLower = ref.toLowerCase(java.util.Locale.ROOT);
            for (var allowed : lowered) {
                if (refLower.contains(allowed) || allowed.contains(refLower)) return true;
            }
            return false;
        };

        var backtick = BACKTICKED_REF.matcher(fragment);
        while (backtick.find()) {
            var raw = backtick.group(1).strip();
            if (raw.isEmpty() || isPlainPhrase(raw)) continue;
            if (!covered.test(raw)) return raw;
        }

        // Strip code spans so PATHISH_WORD only scans free prose.
        var prose = BACKTICKED_REF.matcher(fragment).replaceAll(" ");
        var pathish = PATHISH_WORD.matcher(prose);
        while (pathish.find()) {
            var raw = pathish.group(1).strip();
            if (raw.length() < 4) continue;            // skip noise
            if (!covered.test(raw)) return raw;
        }

        return null;
    }

    /** Skip phrases like "GET /loan-decision" or "pretty status text" that aren't single identifiers. */
    private static boolean isPlainPhrase(String s) {
        if (s.contains(" ")) {
            // multi-word backtick content: only treat as a reference when it
            // is a simple "VERB /path" form; otherwise it's prose-in-backticks
            // we should not police.
            var parts = s.split("\\s+", 2);
            if (parts.length == 2 && parts[0].matches("[A-Z]{3,7}") && parts[1].startsWith("/")) return false;
            return true;
        }
        return false;
    }

    /** Detect models that loop on the same line - simple frequency check. */
    private static boolean looksRepetitive(String fragment) {
        var lines = Arrays.stream(fragment.split("\n"))
            .map(s -> s.strip().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty())
            .toList();
        if (lines.size() < 4) return false;
        var first = lines.get(0);
        int matches = 0;
        for (var l : lines) {
            if (l.equals(first)) matches++;
        }
        return matches >= 4;
    }
}
