package com.acltabontabon.launchpad.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the synthesised-prompt markdown produced by
 * {@link TaskAdvisorService#synthesise} before it is written to disk. Mirrors
 * the role of {@code OutputValidator} on the scan path: returns a list of
 * human-readable warnings (empty = passed) so the caller can either retry the
 * LLM call once or surface the warnings on the result screen.
 * <p>
 * Failures we catch:
 * <ul>
 *   <li>Missing required {@code ## Goal} / {@code ## Constraints} headings -
 *       the assembler should always emit them; absence means assembly broke.</li>
 *   <li>Goal section empty or only whitespace - the LLM produced nothing usable
 *       and the user-task fallback also failed.</li>
 *   <li>Goal section shorter than {@value #GOAL_MIN_WORDS} words - structurally
 *       present but too thin to act on. Retriable: a synthesis retry with the
 *       stricter reminder usually fixes it.</li>
 *   <li>Acceptance section without a single bullet - grounding stripped
 *       everything and no fallback fired.</li>
 *   <li>Acceptance bullets that match the filler whitelist ("works correctly",
 *       "works as expected", etc.) - cosmetic; surfaced so the user sees the
 *       brief is less substantial than it looks. The deterministic safety-net
 *       bullet emitted by the assembler is exempt when it stands alone.</li>
 *   <li>Acceptance bullets shorter than {@value #BULLET_MIN_WORDS} words - same
 *       intent as the filler whitelist, in case the model produces a short
 *       throwaway phrase that didn't match the whitelist verbatim.</li>
 *   <li>Placeholder tokens that leaked through ({@code <id>}, {@code <TBD>},
 *       {@code {{slot}}}, etc.) - shape that the model is contractually not
 *       allowed to emit but small models sometimes do.</li>
 *   <li>Quote-only acceptance bullets ({@code - "create new api"}) - the
 *       upstream grounding filter should remove these, so seeing one here means
 *       the filter regressed.</li>
 * </ul>
 */
public final class TaskOutputValidator {

    private static final Pattern PLACEHOLDER = Pattern.compile(
        "<(id|TBD|placeholder|name|value|tbd|todo)>|\\{\\{[^}]+\\}\\}");

    /** Goals shorter than this many words are flagged as "too short to be
     *  actionable". 8 chosen empirically: the deterministic safety-net Goal
     *  fallback ("(no goal provided)") is 3 words, and even a minimally useful
     *  Goal like "Create the new greeting endpoint." is 5 - so 8 catches model
     *  shrugs ("Build the feature.") without flagging legitimate short Goals. */
    static final int GOAL_MIN_WORDS = 8;
    /** Bullets shorter than this many words are flagged as filler. 3 lets
     *  "Returns paginated results" through while catching "Works correctly" /
     *  "Is implemented" / "Done". */
    static final int BULLET_MIN_WORDS = 3;
    /** Bullet phrases that read as filler even though they pass the word-count
     *  check. Lowercased, with trailing period stripped before comparison. */
    private static final Set<String> FILLER_BULLETS = Set.of(
        "works correctly",
        "works as expected",
        "functions properly",
        "behaves as described",
        "is implemented",
        "is complete",
        "as expected"
    );
    /** Exact text of the deterministic safety-net bullet from
     *  {@link TaskAdvisorService#assembleFinalMarkdown}. When this stands as
     *  the only acceptance bullet it is by design, not LLM filler, so the
     *  filler / short-bullet checks skip it. */
    private static final String FALLBACK_ACCEPTANCE_BULLET =
        "behaviour described in the goal section is implemented.";

    public List<String> validate(String markdown) {
        var warnings = new ArrayList<String>();
        if (markdown == null || markdown.isBlank()) {
            warnings.add("synthesised prompt was empty");
            return warnings;
        }
        if (!markdown.contains("## Goal")) {
            warnings.add("missing required section: ## Goal");
        }
        if (!markdown.contains("## Constraints")) {
            warnings.add("missing required section: ## Constraints");
        }

        var goalBody = sectionBody(markdown, "## Goal");
        if (goalBody.isBlank()) {
            warnings.add("Goal section is empty");
        } else if (wordCount(goalBody) < GOAL_MIN_WORDS) {
            warnings.add("Goal section is too short to be actionable");
        }

        var acceptanceBody = sectionBody(markdown, "## Acceptance criteria");
        if (!acceptanceBody.isBlank() && !hasBullet(acceptanceBody)) {
            warnings.add("Acceptance criteria section has no bullets");
        }
        if (acceptanceBody.lines().anyMatch(TaskOutputValidator::isQuoteOnlyBullet)) {
            warnings.add("Acceptance criteria contains quote-only bullets");
        }

        var bulletTexts = bulletContents(acceptanceBody);
        boolean fallbackOnly = bulletTexts.size() == 1
            && normaliseBulletForCompare(bulletTexts.get(0)).equals(FALLBACK_ACCEPTANCE_BULLET);
        if (!fallbackOnly) {
            if (bulletTexts.stream().anyMatch(TaskOutputValidator::isFillerBullet)) {
                warnings.add("Acceptance criteria contains vague filler bullets");
            }
            if (bulletTexts.stream().anyMatch(b -> wordCount(b) < BULLET_MIN_WORDS)) {
                warnings.add("Acceptance criteria has bullets shorter than "
                    + BULLET_MIN_WORDS + " words");
            }
        }

        if (PLACEHOLDER.matcher(markdown).find()) {
            warnings.add("output contains unresolved placeholder tokens");
        }
        return warnings;
    }

    /** Body text between {@code heading} and the next {@code ## } heading, or end of doc. */
    static String sectionBody(String markdown, String heading) {
        int start = markdown.indexOf(heading);
        if (start < 0) return "";
        start = markdown.indexOf('\n', start);
        if (start < 0) return "";
        start++;
        int end = markdown.indexOf("\n## ", start);
        if (end < 0) end = markdown.length();
        return markdown.substring(start, end).strip();
    }

    private static boolean hasBullet(String body) {
        return body.lines().anyMatch(l -> {
            var s = l.strip();
            return s.startsWith("- ") || s.startsWith("* ");
        });
    }

    private static boolean isQuoteOnlyBullet(String line) {
        var s = line.strip();
        if (!(s.startsWith("- ") || s.startsWith("* "))) return false;
        s = s.substring(2).strip();
        if (s.length() < 2) return false;
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        return (first == '"' && last == '"') || (first == '\'' && last == '\'');
    }

    /** Bullet contents (text after the leading {@code - } / {@code * } marker)
     *  from a section body. Lines that are not bullets are skipped. */
    private static List<String> bulletContents(String body) {
        var out = new ArrayList<String>();
        if (body == null || body.isBlank()) return out;
        for (var raw : body.split("\\R")) {
            var l = raw.strip();
            if (l.startsWith("- ") || l.startsWith("* ")) {
                out.add(l.substring(2).strip());
            }
        }
        return out;
    }

    private static boolean isFillerBullet(String text) {
        return FILLER_BULLETS.contains(normaliseBulletForCompare(text));
    }

    private static String normaliseBulletForCompare(String text) {
        var t = text == null ? "" : text.strip().toLowerCase();
        if (t.endsWith(".") || t.endsWith("!")) t = t.substring(0, t.length() - 1).strip();
        return t;
    }

    private static int wordCount(String text) {
        if (text == null) return 0;
        var t = text.strip();
        if (t.isEmpty()) return 0;
        return t.split("\\s+").length;
    }
}
