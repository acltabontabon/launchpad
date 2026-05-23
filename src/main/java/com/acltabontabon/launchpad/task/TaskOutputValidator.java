package com.acltabontabon.launchpad.task;

import java.util.ArrayList;
import java.util.List;
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
 *   <li>Acceptance section without a single bullet - grounding stripped
 *       everything and no fallback fired.</li>
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
        }

        var acceptanceBody = sectionBody(markdown, "## Acceptance criteria");
        if (!acceptanceBody.isBlank() && !hasBullet(acceptanceBody)) {
            warnings.add("Acceptance criteria section has no bullets");
        }
        if (acceptanceBody.lines().anyMatch(TaskOutputValidator::isQuoteOnlyBullet)) {
            warnings.add("Acceptance criteria contains quote-only bullets");
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
}
