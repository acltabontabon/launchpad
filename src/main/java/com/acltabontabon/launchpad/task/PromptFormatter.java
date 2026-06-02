package com.acltabontabon.launchpad.task;

import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import java.util.List;

/**
 * Renders the small pieces of text fed into prompt templates and into the
 * final assembled markdown: stack one-liner, Q&amp;A transcript, compact
 * one-line-per-item interview menus, and a single rule bullet for the
 * Constraints section. Also splits a SYSTEM/USER prompt template via
 * {@link PromptParts}. Pure formatting; no LLM calls and no state.
 */
public final class PromptFormatter {

    private static final String SYSTEM_MARKER = "===SYSTEM===";
    private static final String USER_MARKER = "===USER===";

    private PromptFormatter() {}

    /**
     * Splits a prompt template on the {@code ===SYSTEM===} / {@code ===USER===}
     * markers so the same .txt file can carry both messages. Falls back to
     * treating the whole template as the user message if either marker is
     * missing - keeps tests and ad-hoc templates working without a system block.
     */
    public record PromptParts(String system, String user) {
        public static PromptParts split(String template) {
            if (template == null) return new PromptParts("", "");
            int sysIdx = template.indexOf(SYSTEM_MARKER);
            int userIdx = template.indexOf(USER_MARKER);
            if (sysIdx < 0 || userIdx < 0 || userIdx < sysIdx) {
                return new PromptParts("", template);
            }
            var system = template.substring(sysIdx + SYSTEM_MARKER.length(), userIdx).strip();
            var user = template.substring(userIdx + USER_MARKER.length()).strip();
            return new PromptParts(system, user);
        }
    }

    public static String formatStack(StackProfile stack) {
        if (stack == null) return "(unknown stack)";
        var name = stack.displayName();
        return name == null || name.isBlank() ? "(unknown stack)" : name;
    }

    public static String formatHistory(List<TaskTurn> history) {
        if (history == null || history.isEmpty()) return "(no questions asked yet)";
        var sb = new StringBuilder();
        int i = 1;
        for (var turn : history) {
            sb.append("Q").append(i).append(": ").append(turn.question()).append("\n");
            sb.append("A").append(i).append(": ").append(turn.answer()).append("\n\n");
            i++;
        }
        return sb.toString().stripTrailing();
    }

    public static String formatRulesCompact(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) return "(no rules apply to this task)";
        var sb = new StringBuilder();
        for (var r : rules) {
            sb.append("- rule:").append(r.id());
            if (r.title() != null && !r.title().isBlank()) sb.append("  -  ").append(r.title());
            if (r.severity() != null && !r.severity().isBlank()) sb.append("  [").append(r.severity()).append("]");
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static String formatSkillsCompact(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) return "(no skills apply to this task)";
        var sb = new StringBuilder();
        for (var s : skills) {
            sb.append("- skill:").append(s.id());
            if (s.title() != null && !s.title().isBlank()) sb.append("  -  ").append(s.title());
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static String formatChecklistsCompact(List<Checklist> checklists) {
        if (checklists == null || checklists.isEmpty()) return "(no checklists apply to this task)";
        var sb = new StringBuilder();
        for (var c : checklists) {
            sb.append("- checklist:").append(c.id());
            if (c.title() != null && !c.title().isBlank()) sb.append("  -  ").append(c.title());
            sb.append("\n");
            if (c.items() != null) {
                for (ChecklistItem item : c.items()) {
                    sb.append("    - ").append(item.id()).append(": ").append(item.text()).append("\n");
                }
            }
        }
        return sb.toString().stripTrailing();
    }

    /** Renders a single rule as one Constraints bullet: severity prefix, first
     *  sentence of the description (or title fallback), id suffix in italics. */
    public static String renderRuleBullet(Rule r) {
        var sb = new StringBuilder();
        sb.append("- ");
        if (r.severity() != null && !r.severity().isBlank()) {
            sb.append("[").append(r.severity()).append("] ");
        }
        var directive = TaskTextSupport.firstSentence(r.description());
        if (directive.isBlank()) {
            directive = r.title() == null ? r.id() : r.title();
        }
        sb.append(directive);
        if (!directive.endsWith(".") && !directive.endsWith("!") && !directive.endsWith("?")) {
            sb.append(".");
        }
        sb.append("  *(").append(r.id()).append(")*\n");
        return sb.toString();
    }
}
