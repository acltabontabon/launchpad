package com.acltabontabon.launchpad.task;

import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import com.acltabontabon.launchpad.standards.Skill;
import java.util.List;
import java.util.Set;

/**
 * Picks the subset of rules / skills / checklists that apply to a task. Applies
 * scope matching (framework, language, tags) plus opt-out filtering derived from
 * the interview transcript via {@link TaskClassifier}. Pure function;
 * idempotent, safe to call on a background thread.
 * <p>
 * Also owns the stack-axis normalisation ({@code Spring Boot} -&gt; {@code spring-boot})
 * and the severity ranking used by {@link MarkdownPostProcessor} when sorting
 * constraints in the final markdown.
 */
public final class StandardsSelector {

    private StandardsSelector() {}

    /**
     * Apply scope + opt-out filtering once. Called by the runner when the user
     * lands on TASK_INTERVIEW (and again from synthesise to re-derive after the
     * interview may have surfaced new opt-outs).
     */
    public static RelevantStandards selectRelevantStandards(
        StackProfile stack,
        String taskDescription,
        List<TaskTurn> history,
        List<Rule> allRules,
        List<Skill> allSkills,
        List<Checklist> allChecklists
    ) {
        var taskTags = TaskClassifier.classifyTaskTags(taskDescription, history);
        var optedOutRuleIds = TaskClassifier.detectOptedOutRules(allRules, history);
        var optedOutTags = TaskClassifier.detectOptedOutTags(history);

        var rules = (allRules == null ? List.<Rule>of() : allRules).stream()
            .filter(r -> scopeApplies(r.scope(), stack, taskTags))
            .filter(r -> !optedOutRuleIds.contains(r.id()))
            .filter(r -> !overlapsTags(r.scope(), optedOutTags))
            .toList();
        var skills = (allSkills == null ? List.<Skill>of() : allSkills).stream()
            .filter(s -> scopeApplies(s.scope(), stack, taskTags))
            .filter(s -> !overlapsTags(s.scope(), optedOutTags))
            .toList();
        var checklists = (allChecklists == null ? List.<Checklist>of() : allChecklists).stream()
            .filter(c -> scopeApplies(c.scope(), stack, taskTags))
            .filter(c -> !overlapsTags(c.scope(), optedOutTags))
            .toList();
        return new RelevantStandards(rules, skills, checklists);
    }

    /** True when the item's scope axes (frameworks / languages / tags / tasks)
     *  either are empty (no restriction) or overlap the project / task. */
    public static boolean scopeApplies(Scope scope, StackProfile stack, Set<String> taskTags) {
        if (scope == null) return true;
        String fwSlug = normaliseFramework(stack);
        String langSlug = normaliseLanguage(stack);
        if (!matchAxis(scope.frameworks(), fwSlug)) return false;
        if (!matchAxis(scope.languages(), langSlug)) return false;
        if (!matchAxisAny(scope.tags(), taskTags)) return false;
        if (!matchAxisAny(scope.tasks(), taskTags)) return false;
        return true;
    }

    /** True if the item's scope tags overlap any opted-out tag. */
    public static boolean overlapsTags(Scope scope, Set<String> optedOutTags) {
        if (scope == null || optedOutTags == null || optedOutTags.isEmpty()) return false;
        if (scope.tags() == null || scope.tags().isEmpty()) return false;
        return scope.tags().stream().anyMatch(t -> t != null && optedOutTags.contains(t.toLowerCase()));
    }

    /** Normalises StackProfile.framework ("Spring Boot") to the YAML slug ("spring-boot"). */
    public static String normaliseFramework(StackProfile stack) {
        if (stack == null || stack.framework() == null) return null;
        return stack.framework().toLowerCase().replaceAll("\\s+", "-").replaceAll("\\.", "");
    }

    /** Normalises StackProfile.language ("Java") to the YAML slug ("java"). */
    public static String normaliseLanguage(StackProfile stack) {
        if (stack == null || stack.language() == null) return null;
        return stack.language().toLowerCase();
    }

    /** Ranks rule severity for stable ordering: must=0, never=1, should=2, avoid=3, other=4. */
    public static int severityRank(String severity) {
        if (severity == null) return 4;
        return switch (severity.toLowerCase().strip()) {
            case "must" -> 0;
            case "never" -> 1;
            case "should" -> 2;
            case "avoid" -> 3;
            default -> 4;
        };
    }

    private static boolean matchAxis(List<String> ruleValues, String projectValue) {
        if (ruleValues == null || ruleValues.isEmpty()) return true;
        if (projectValue == null || projectValue.isBlank()) return false;
        var target = projectValue.toLowerCase();
        return ruleValues.stream().anyMatch(v -> v != null && v.toLowerCase().equals(target));
    }

    private static boolean matchAxisAny(List<String> ruleValues, Set<String> projectValues) {
        if (ruleValues == null || ruleValues.isEmpty()) return true;
        if (projectValues == null || projectValues.isEmpty()) return false;
        return ruleValues.stream().anyMatch(v -> v != null && projectValues.contains(v.toLowerCase()));
    }
}
