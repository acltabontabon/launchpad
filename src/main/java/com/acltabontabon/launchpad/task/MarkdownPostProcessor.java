package com.acltabontabon.launchpad.task;

import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses the marker-delimited LLM output and assembles the final markdown.
 * Owns the fixed pipeline:
 * <ol>
 *   <li>{@link #parseMarkerSections} - split GOAL / ACCEPTANCE / OUT_OF_SCOPE
 *   <li>{@link #separateGoalProseFromBullets} - pull stray bullets out of GOAL
 *   <li>{@link #groundAcceptance} - drop quote-only and hallucinated bullets
 *   <li>{@link #groundOutOfScope} - keep only bullets the user opted out of
 *   <li>{@link #assembleFinalMarkdown} - render the 5-section document
 * </ol>
 * The ordering is load-bearing: each stage assumes its predecessors ran.
 * Stateless; safe to call from any thread.
 */
public final class MarkdownPostProcessor {

    /** Cap on rules embedded in the Constraints section. Higher counts overwhelm
     *  the implementing agent and the prompt context window. Overflow rules are
     *  cited by id in a footer line so they remain discoverable. */
    public static final int MAX_CONSTRAINTS = 10;

    static final List<String> MARKER_NAMES = List.of(
        "GOAL", "ACCEPTANCE", "OUT_OF_SCOPE"
    );

    private MarkdownPostProcessor() {}

    /** Parsed LLM output sections. Context was tried twice and hallucinated both
     *  times - the model abstracts the example shape and invents content. Acceptance
     *  + Goal capture the user's decisions adequately; a separate Context isn't
     *  worth the hallucination risk. */
    public record FinalizeSections(String goal, String acceptance, String outOfScope) {}

    /**
     * Splits the marker-delimited LLM response. Missing sections default to safe
     * empty content - we never throw because of a misbehaving model.
     */
    public static FinalizeSections parseMarkerSections(String raw) {
        var clean = raw == null ? "" : raw.strip();
        return new FinalizeSections(
            extractMarkerSection(clean, "GOAL"),
            extractMarkerSection(clean, "ACCEPTANCE"),
            extractMarkerSection(clean, "OUT_OF_SCOPE")
        );
    }

    /**
     * GOAL expects a prose paragraph but the model often emits "&lt;paragraph&gt;\n\n-
     * bullet\n- bullet". Take only the leading prose for GOAL and append the
     * trailing bullets to ACCEPTANCE - the grounding filter and downstream
     * acceptance assembly can then handle them properly.
     */
    public static FinalizeSections separateGoalProseFromBullets(FinalizeSections sections) {
        if (sections == null || sections.goal() == null || sections.goal().isBlank()) return sections;
        var proseLines = new ArrayList<String>();
        var bulletLines = new ArrayList<String>();
        boolean inBullets = false;
        for (var raw : sections.goal().split("\\R")) {
            var l = raw.strip();
            if (l.isEmpty()) {
                if (!inBullets) proseLines.add("");  // preserve paragraph breaks before bullets
                continue;
            }
            if (l.startsWith("- ") || l.startsWith("* ")) {
                inBullets = true;
                bulletLines.add(l);
            } else if (inBullets) {
                int last = bulletLines.size() - 1;
                if (last >= 0) bulletLines.set(last, bulletLines.get(last) + " " + l);
            } else {
                proseLines.add(l);
            }
        }
        var prose = String.join(" ", proseLines).replaceAll(" +", " ").strip();
        if (bulletLines.isEmpty()) return sections;
        var mergedAcceptance = String.join("\n", bulletLines);
        if (sections.acceptance() != null && !sections.acceptance().isBlank()) {
            mergedAcceptance = mergedAcceptance + "\n" + sections.acceptance();
        }
        return new FinalizeSections(prose, mergedAcceptance, sections.outOfScope());
    }

    /**
     * Filter Acceptance bullets to only those grounded in the user's actual task
     * or interview answers. Drops two failure modes:
     *   (a) quote-only bullets like {@code "create new api"} - the model echoing
     *       user fragments verbatim as bullets instead of producing real criteria.
     *   (b) bullets whose significant words don't appear in the user's task or
     *       answers at all - typically hallucinations the model invented (e.g.
     *       "internationalization", "encryption", "WebSocket connection").
     * Keeps the standard fallback bullet ("matches the task description...")
     * since it's intentionally generic.
     */
    public static FinalizeSections groundAcceptance(FinalizeSections sections, String userTask, List<TaskTurn> history) {
        if (sections == null || sections.acceptance() == null || sections.acceptance().isBlank()) {
            return sections;
        }
        var grounding = new HashSet<String>();
        grounding.addAll(TaskTextSupport.significantWords(userTask));
        if (history != null) {
            for (var turn : history) {
                if (turn != null && turn.answer() != null) {
                    grounding.addAll(TaskTextSupport.significantWords(turn.answer()));
                }
            }
        }
        if (grounding.isEmpty()) return sections;

        var kept = new ArrayList<String>();
        for (var raw : sections.acceptance().split("\\R")) {
            var l = raw.strip();
            if (l.isEmpty()) continue;
            var text = stripAllBulletPrefixes(l);

            if (text.toLowerCase().contains("matches the task description")) {
                kept.add(l);
                continue;
            }

            var unquoted = text;
            if ((unquoted.startsWith("\"") && unquoted.endsWith("\""))
                || (unquoted.startsWith("'") && unquoted.endsWith("'"))) {
                continue;
            }

            var bulletWords = TaskTextSupport.significantWords(text);
            if (bulletWords.isEmpty()) continue;
            if (bulletWords.stream().anyMatch(grounding::contains)) {
                kept.add(l);
            }
        }
        return new FinalizeSections(sections.goal(), String.join("\n", kept), sections.outOfScope());
    }

    /**
     * Drop Out-of-scope bullets that aren't grounded in something the user
     * actually opted out of. The grounding set is built from significant words of
     * (a) every interview question whose answer was a negation, and (b) titles of
     * any rule that was opted out. A bullet survives only if its significant
     * words overlap that grounding set.
     */
    public static FinalizeSections groundOutOfScope(
        FinalizeSections sections,
        List<TaskTurn> history,
        Set<String> optedOutRuleIds,
        List<Rule> allRules
    ) {
        if (sections == null || sections.outOfScope() == null || sections.outOfScope().isBlank()) {
            return sections;
        }
        var grounding = buildOptOutGrounding(history, optedOutRuleIds, allRules);
        if (grounding.isEmpty()) {
            return new FinalizeSections(sections.goal(), sections.acceptance(), "");
        }
        var kept = new ArrayList<String>();
        for (var raw : sections.outOfScope().split("\\R")) {
            var l = raw.strip();
            if (l.isEmpty()) continue;
            var text = l.startsWith("- ") ? l.substring(2)
                : l.startsWith("* ") ? l.substring(2) : l;
            // Skip placeholder text like "(none)" or "(no exclusions)" - the
            // synthesise prompt's documented sentinel when the user did not opt
            // out of anything.
            if (text.startsWith("(") && text.endsWith(")")) continue;
            var bulletWords = TaskTextSupport.significantWords(text);
            if (bulletWords.stream().anyMatch(grounding::contains)) {
                kept.add(l);
            }
        }
        return new FinalizeSections(sections.goal(), sections.acceptance(),
            String.join("\n", kept));
    }

    /**
     * Builds the final markdown deterministically. The LLM's contribution is the
     * prose of Goal / Acceptance / Out-of-scope; everything else is generated
     * from the standards pack. Null-safe on {@code userTask} - the assembler
     * runs even when called with a null task description.
     * <p>
     * Sections are kept compact: constraints render as one-line bullets (first
     * sentence of the rule's content + severity + id suffix for traceability)
     * rather than ###-headed blocks with embedded rationale. The downstream
     * implementing agent needs the actionable directive, not the persuasive
     * argument that convinced the human to ship the rule.
     */
    public static String assembleFinalMarkdown(
        String userTask,
        FinalizeSections sections,
        List<Rule> rules,
        List<Skill> skills,
        List<Checklist> checklists
    ) {
        var sb = new StringBuilder();
        var safeTask = userTask == null ? "" : userTask.strip();

        sb.append("## Goal\n");
        var goal = sections.goal() == null || sections.goal().isBlank() ? safeTask : sections.goal();
        if (goal.isBlank()) goal = "(no goal provided)";
        sb.append(goal).append("\n\n");

        sb.append("## Constraints\n");
        if (rules == null || rules.isEmpty()) {
            sb.append("- _No standards rules apply to this task._\n\n");
        } else {
            var sorted = rules.stream()
                .sorted((a, b) -> {
                    int s = Integer.compare(
                        StandardsSelector.severityRank(a.severity()),
                        StandardsSelector.severityRank(b.severity()));
                    if (s != 0) return s;
                    return Integer.compare(a.priorityValue(), b.priorityValue());
                })
                .toList();
            int cap = Math.min(MAX_CONSTRAINTS, sorted.size());
            for (int i = 0; i < cap; i++) sb.append(PromptFormatter.renderRuleBullet(sorted.get(i)));
            if (sorted.size() > cap) {
                var overflowIds = sorted.subList(cap, sorted.size()).stream()
                    .map(Rule::id).toList();
                sb.append("- _Also applicable (")
                  .append(overflowIds.size())
                  .append(" more): ")
                  .append(String.join(", ", overflowIds))
                  .append("_\n");
            }
            sb.append("\n");
        }

        sb.append("## Acceptance criteria\n");
        sb.append(normaliseBullets(sections.acceptance(),
            "- Behaviour described in the Goal section is implemented."));
        sb.append("\n");

        if (sections.outOfScope() != null && !sections.outOfScope().isBlank()) {
            sb.append("## Out of scope\n");
            sb.append(normaliseBullets(sections.outOfScope(), ""));
            sb.append("\n");
        }

        boolean hasSkills = skills != null && !skills.isEmpty();
        boolean hasChecklists = checklists != null && !checklists.isEmpty();
        if (hasSkills || hasChecklists) {
            sb.append("## Standards consulted\n");
            if (hasSkills) {
                for (var s : skills) {
                    sb.append("- skill:").append(s.id());
                    if (s.title() != null && !s.title().isBlank()) sb.append(" - ").append(s.title());
                    sb.append("\n");
                }
            }
            if (hasChecklists) {
                for (var c : checklists) {
                    sb.append("- checklist:").append(c.id());
                    if (c.title() != null && !c.title().isBlank()) sb.append(" - ").append(c.title());
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString().stripTrailing() + "\n";
    }

    /**
     * Find a marker section by name. The matcher is intentionally lenient:
     * models routinely emit variations like {@code ===NAME===}, {@code ---NAME---},
     * {@code ===NAME---}, or even drop one delimiter entirely. We accept any
     * combination of {@code =} or {@code -} (3+) on either side. Without this
     * leniency a single bad delimiter swallows two sections into one.
     */
    private static String extractMarkerSection(String text, String name) {
        var marker = java.util.regex.Pattern.compile(
            "[=\\-]{3,}\\s*" + java.util.regex.Pattern.quote(name) + "\\s*[=\\-]{3,}");
        var m = marker.matcher(text);
        if (!m.find()) return "";
        int contentStart = m.end();
        int end = text.length();
        for (var other : MARKER_NAMES) {
            if (other.equals(name)) continue;
            var otherMarker = java.util.regex.Pattern.compile(
                "[=\\-]{3,}\\s*" + java.util.regex.Pattern.quote(other) + "\\s*[=\\-]{3,}");
            var om = otherMarker.matcher(text);
            if (om.find(contentStart) && om.start() < end) {
                end = om.start();
            }
        }
        return text.substring(contentStart, end).strip();
    }

    private static Set<String> buildOptOutGrounding(
        List<TaskTurn> history,
        Set<String> optedOutRuleIds,
        List<Rule> allRules
    ) {
        var keywords = new HashSet<String>();
        if (history != null) {
            for (var turn : history) {
                if (turn == null) continue;
                if (TaskClassifier.isNegation(turn.answer())) {
                    keywords.addAll(TaskTextSupport.significantWords(
                        turn.question() == null ? "" : turn.question()));
                }
            }
        }
        if (allRules != null && optedOutRuleIds != null) {
            for (var rule : allRules) {
                if (optedOutRuleIds.contains(rule.id()) && rule.title() != null) {
                    keywords.addAll(TaskTextSupport.significantWords(rule.title()));
                }
            }
        }
        return keywords;
    }

    private static String normaliseBullets(String section, String defaultLine) {
        if (section == null || section.isBlank()) return defaultLine + "\n";
        var bullets = new ArrayList<String>();
        for (var raw : section.split("\\R")) {
            var l = raw.strip();
            if (l.isEmpty()) continue;
            if (l.startsWith("- ") || l.startsWith("* ")) {
                bullets.add(stripAllBulletPrefixes(l));
            } else if (bullets.isEmpty()) {
                bullets.add(l);
            } else {
                int last = bullets.size() - 1;
                bullets.set(last, bullets.get(last) + " " + l);
            }
        }
        if (bullets.isEmpty()) return defaultLine + "\n";
        var sb = new StringBuilder();
        for (var b : bullets) sb.append("- ").append(b).append("\n");
        return sb.toString();
    }

    private static String stripAllBulletPrefixes(String line) {
        var l = line.strip();
        while (l.startsWith("- ") || l.startsWith("* ")) {
            l = l.substring(2).strip();
        }
        return l;
    }
}
