package com.acltabontabon.launchpad.task;

import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Rule;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure decisions around interview-question shape: extracting a model's question
 * from noisy output, detecting near-duplicates of prior asks, picking the next
 * uncovered must-rule when the model declares "done" prematurely, and turning
 * the critic's raw response into a verdict. No LLM calls; tested in isolation.
 */
public final class InterviewQuestionPlanner {

    private InterviewQuestionPlanner() {}

    /** Sentinel returned by the interview model (and by {@link #extractQuestion}
     *  on empty input) to signal "no further question; proceed to synthesise". */
    public static final String DONE_TOKEN = "__DONE__";
    /** Critic verdict that means "the brief is substantial enough; proceed to
     *  synthesise". */
    public static final String READY_TOKEN = "__OK__";

    /**
     * Stack-aware DISCOVERY hint: instead of a fixed REST-shaped noun list, the
     * Java side composes the under-specified aspects most likely relevant given
     * the task wording. A bug-fix task hears about symptoms and reproduction; a
     * REST task hears about resource and callers; an unclassified task gets a
     * neutral fallback. The prompt picks one of these to probe first.
     */
    public static String discoveryHintFor(String taskDescription, List<TaskTurn> history) {
        var tags = TaskClassifier.classifyTaskTags(taskDescription, history);
        if (tags.contains("debugging")) {
            return "symptom, reproduction steps, root-cause hypothesis, expected behaviour, affected scope";
        }
        if (tags.contains("refactoring")) {
            return "target module, motivation, behaviour-preservation criteria, scope boundary";
        }
        if (tags.contains("rest")) {
            return "resource, HTTP method, inputs, outputs, callers, success criteria, error shape";
        }
        if (tags.contains("ui")) {
            return "screen / view, user action, inputs, success / failure state, navigation context";
        }
        if (tags.contains("data")) {
            return "schema target, migration safety, query path, expected row counts, rollback plan";
        }
        if (tags.contains("ai")) {
            return "model role, prompt inputs, expected output shape, failure handling, observability";
        }
        if (tags.contains("configuration")) {
            return "setting name, default, source of truth, who can change it at runtime";
        }
        return "purpose, primary inputs, primary outputs, callers / triggers, success criteria";
    }

    /**
     * Finds the next applicable [must]-severity rule whose title words don't
     * overlap with anything asked in the interview so far. "Covered" = 2+
     * significant title words appear in some prior question. Returns null if all
     * applicable must-rules are already covered.
     */
    public static Rule pickNextUncoveredMustRule(
        List<TaskTurn> history,
        List<Rule> rules,
        StackProfile stack,
        Set<String> taskTags,
        Set<String> optedOutIds
    ) {
        if (rules == null || rules.isEmpty()) return null;
        var coveredWords = new HashSet<String>();
        if (history != null) {
            for (var turn : history) {
                if (turn != null && turn.question() != null) {
                    coveredWords.addAll(TaskTextSupport.significantWords(turn.question()));
                }
            }
        }
        return rules.stream()
            .filter(r -> "must".equalsIgnoreCase(r.severity()))
            .filter(r -> optedOutIds == null || !optedOutIds.contains(r.id()))
            .filter(r -> StandardsSelector.scopeApplies(r.scope(), stack, taskTags))
            .filter(r -> {
                var titleWords = TaskTextSupport.significantWords(r.title() == null ? "" : r.title());
                if (titleWords.isEmpty()) return false;
                long covered = titleWords.stream().filter(coveredWords::contains).count();
                return covered < Math.min(2, titleWords.size());
            })
            .findFirst()
            .orElse(null);
    }

    /** Deterministic question wrapper around a rule title. */
    public static String synthesizeStandardsQuestion(Rule rule) {
        var title = rule.title() == null ? rule.id() : rule.title();
        return "Regarding the team's \"" + title
            + "\" standard - how should this apply to your task (or do you want to skip it)?";
    }

    /**
     * Defensive parse. Local models often ignore the prompt's "output ONLY the
     * question" rule and emit preamble like "Sure! Let me ask: ...". We pick the
     * actual question out of whatever they returned:
     *   1. If response is empty or contains __DONE__, finalize.
     *   2. Otherwise prefer the LAST line ending in '?' - that's almost always
     *      the real question, regardless of how much preamble preceded it.
     *   3. Fallback to the last non-empty line.
     */
    public static String extractQuestion(String raw) {
        if (raw == null) return DONE_TOKEN;
        var stripped = raw.strip();
        if (stripped.isEmpty()) return DONE_TOKEN;
        if (stripped.contains(DONE_TOKEN)) return DONE_TOKEN;

        var lines = stripped.split("\\R");
        String lastQuestion = null;
        String lastNonEmpty = null;
        for (var line : lines) {
            var l = line.strip();
            if (l.isEmpty()) continue;
            lastNonEmpty = l;
            if (l.endsWith("?")) lastQuestion = l;
        }
        var picked = lastQuestion != null ? lastQuestion : (lastNonEmpty != null ? lastNonEmpty : DONE_TOKEN);
        // Strip Q-numbering prefixes the model picks up from the history format
        // ("Q1:", "Q3.", "1.", "**Q2:**", etc.). The history we feed in is
        // labeled Q1/A1/Q2/A2 and models leak that label into their own output.
        return picked.replaceFirst("^\\**\\s*(Q\\d+|\\d+)\\s*[:.\\)]\\s*\\**\\s*", "");
    }

    /**
     * True when {@code newQuestion} overlaps any prior question heavily enough
     * that re-asking would loop. Both Jaccard (mutual overlap) and containment
     * (prior fully inside the new question) checks fire. Stem-tolerant so
     * "endpoint" / "endpoints" count as the same word.
     */
    public static boolean isNearDuplicateOfPrior(String newQuestion, List<TaskTurn> history) {
        if (history == null || history.isEmpty()) return false;
        var newWords = TaskTextSupport.significantWords(newQuestion);
        if (newWords.size() < 3) return false;  // too short to compare meaningfully
        for (var turn : history) {
            var priorWords = TaskTextSupport.significantWords(turn.question());
            if (priorWords.isEmpty()) continue;
            int matches = TaskTextSupport.stemTolerantOverlap(newWords, priorWords);
            double jaccard = (double) matches / (newWords.size() + priorWords.size() - matches);
            if (jaccard >= 0.5) return true;
            double containment = (double) matches / Math.min(newWords.size(), priorWords.size());
            if (containment >= 0.7) return true;
        }
        return false;
    }

    /**
     * Pure decision function for the critic's raw output. Treats empty /
     * {@link #READY_TOKEN} / a {@link #DONE_TOKEN} mention / a near-duplicate of
     * any prior question all as "ready". Anything else is interpreted as a
     * follow-up question via the same {@link #extractQuestion} parse used by
     * the interview path.
     */
    public static String interpretCritiqueResponse(String raw, List<TaskTurn> history) {
        if (raw == null) return READY_TOKEN;
        var stripped = raw.strip();
        if (stripped.isEmpty()) return READY_TOKEN;
        if (stripped.contains(READY_TOKEN)) return READY_TOKEN;
        if (stripped.contains(DONE_TOKEN)) return READY_TOKEN;
        var question = extractQuestion(raw);
        if (question.equals(DONE_TOKEN)) return READY_TOKEN;
        if (isNearDuplicateOfPrior(question, history)) return READY_TOKEN;
        return question;
    }
}
