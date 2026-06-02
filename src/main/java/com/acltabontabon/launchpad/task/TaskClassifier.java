package com.acltabontabon.launchpad.task;

import com.acltabontabon.launchpad.standards.Rule;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Classifies the task text + interview history into:
 *   - the YAML scope.tags vocabulary used across rules / skills / checklists
 *     ({@link #classifyTaskTags}), so downstream scope matching can decide what
 *     applies;
 *   - per-rule opt-outs derived from negated answers ({@link #detectOptedOutRules}),
 *     so a "no" to an interview question drops the matching rule;
 *   - per-tag opt-outs from negated answers ({@link #detectOptedOutTags}), so a
 *     "no" to an auth question drops the entire security / auth / crypto domain.
 * Pure functions; no state.
 */
public final class TaskClassifier {

    private TaskClassifier() {}

    static final Set<String> NEGATION_TOKENS = Set.of(
        "no", "none", "nope", "nah", "don't", "dont", "do not", "without",
        "skip", "skipped", "skipping", "exclude", "excluded", "no need",
        "not now", "not needed", "n/a", "na", "bypass", "ignore"
    );

    /**
     * Classify the task into a set of tag slugs that map to the YAML scope.tags
     * vocabulary used across rules/skills/checklists. Tokenises the haystack into
     * whole words (so "create new api" doesn't trigger the `ai` tag via substring
     * match of "api"). Every task implicitly gets "feature" as a baseline.
     */
    public static Set<String> classifyTaskTags(String taskDescription, List<TaskTurn> history) {
        var words = tokenize(buildHaystack(taskDescription, history));
        var tags = new HashSet<String>();
        tags.add("feature");
        if (anyWord(words, "api", "endpoint", "controller", "route", "rest", "http", "endpoints")) {
            tags.add("rest");
            tags.add("http");
            tags.add("delivery");
            if (anyWord(words, "list", "lists", "search", "browse", "all", "many",
                    "collection", "collections", "page", "paginate", "paginated")) {
                tags.add("rest-collection");
            }
            if (anyWord(words, "post", "put", "patch", "delete", "create", "creates",
                    "update", "updates", "modify", "modifies", "edit", "add", "adds",
                    "save", "saves", "remove", "removes", "destroy", "mutation")) {
                tags.add("rest-mutation");
            }
        }
        if (anyWord(words, "ui", "view", "screen", "form", "render", "tui", "cli")) tags.add("ui");
        if (anyWord(words, "auth", "authentication", "login", "token", "session", "password", "credential")) tags.add("security");
        if (anyWord(words, "database", "migration", "schema", "sql", "query", "table")) tags.add("data");
        if (anyWord(words, "config", "setting", "property", "env", "environment")) tags.add("configuration");
        if (anyWord(words, "test", "spec", "fixture")) tags.add("testing");
        if (anyWord(words, "log", "logging", "metric", "trace", "observability", "monitor")) tags.add("observability");
        if (anyWord(words, "ai", "llm", "model", "prompt", "embedding", "chatclient")) tags.add("ai");
        if (anyWord(words, "refactor", "rename", "extract", "inline")) tags.add("refactoring");
        if (anyWord(words, "bug", "fix", "defect", "regression")) tags.add("debugging");
        return tags;
    }

    /**
     * Maps "negation answer + keyword in the question" -&gt; "this whole tag-domain
     * is opted out". When the user says "no" to a question containing security
     * keywords like "auth" / "password" / "token", we drop EVERY rule whose scope
     * tags include "security" / "crypto" / "auth" - not just the specific rule
     * whose title matched.
     */
    public static Set<String> detectOptedOutTags(List<TaskTurn> history) {
        if (history == null || history.isEmpty()) return Set.of();
        var optedOut = new HashSet<String>();
        for (var turn : history) {
            if (turn == null || turn.question() == null || turn.answer() == null) continue;
            if (!isNegation(turn.answer())) continue;
            var q = turn.question().toLowerCase();
            if (containsAnyKeyword(q, "auth", "authentication", "login", "token", "session", "password", "credential", "security")) {
                optedOut.add("security");
                optedOut.add("auth");
                optedOut.add("crypto");
            }
            if (containsAnyKeyword(q, "rate", "limit", "throttle", "quota")) {
                optedOut.add("reliability");
            }
            if (containsAnyKeyword(q, "log", "logging", "metric", "trace", "observability", "monitor")) {
                optedOut.add("observability");
                optedOut.add("operability");
            }
            if (containsAnyKeyword(q, "pagination", "paginate", "cursor")) {
                optedOut.add("scale");
            }
        }
        return optedOut;
    }

    /** Returns the ids of rules whose title significantly overlaps a negated
     *  question - those rules are dropped from the relevant set. */
    public static Set<String> detectOptedOutRules(List<Rule> rules, List<TaskTurn> history) {
        if (rules == null || rules.isEmpty() || history == null || history.isEmpty()) return Set.of();
        var optedOut = new HashSet<String>();
        for (var turn : history) {
            if (turn.question() == null || turn.answer() == null) continue;
            if (!isNegation(turn.answer())) continue;
            var qWords = TaskTextSupport.significantWords(turn.question());
            if (qWords.isEmpty()) continue;
            for (var rule : rules) {
                if (rule.title() == null) continue;
                var titleWords = TaskTextSupport.significantWords(rule.title());
                if (titleWords.isEmpty()) continue;
                int hits = TaskTextSupport.stemTolerantOverlap(titleWords, qWords);
                if (hits >= Math.min(2, titleWords.size())) {
                    optedOut.add(rule.id());
                }
            }
        }
        return optedOut;
    }

    /** True when the answer reads as a refusal: equal to / starts with / ends
     *  with / contains a {@link #NEGATION_TOKENS} value as a whole word. */
    public static boolean isNegation(String answer) {
        if (answer == null) return false;
        var trimmed = answer.toLowerCase().strip();
        if (trimmed.isEmpty()) return false;
        for (var token : NEGATION_TOKENS) {
            if (trimmed.equals(token) || trimmed.startsWith(token + " ")
                    || trimmed.endsWith(" " + token) || trimmed.contains(" " + token + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String buildHaystack(String taskDescription, List<TaskTurn> history) {
        var sb = new StringBuilder();
        if (taskDescription != null) sb.append(taskDescription.toLowerCase()).append(' ');
        if (history != null) {
            for (var t : history) {
                if (t.question() != null) sb.append(t.question().toLowerCase()).append(' ');
                if (t.answer() != null) sb.append(t.answer().toLowerCase()).append(' ');
            }
        }
        return sb.toString();
    }

    private static Set<String> tokenize(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());
    }

    private static boolean anyWord(Set<String> words, String... needles) {
        for (var n : needles) if (words.contains(n)) return true;
        return false;
    }

    private static boolean containsAnyKeyword(String haystack, String... needles) {
        for (var n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
