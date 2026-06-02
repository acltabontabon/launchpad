package com.acltabontabon.launchpad.task;

import com.acltabontabon.launchpad.ai.PromptSelector;
import com.acltabontabon.launchpad.config.LaunchpadTaskProperties;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Orchestrator for the /new-task interview. Wires together the focused
 * collaborators that own each responsibility:
 * <ul>
 *   <li>{@link StandardsSelector} - scope + opt-out filtering of rules / skills / checklists.
 *   <li>{@link TaskClassifier} - tag classification and negation / opt-out detection.
 *   <li>{@link InterviewQuestionPlanner} - question extraction, duplicate detection,
 *       forced-must-rule fallback, critic-response interpretation.
 *   <li>{@link PromptFormatter} - stack / history / compact menu rendering and
 *       SYSTEM/USER prompt-template splitting.
 *   <li>{@link MarkdownPostProcessor} - marker parsing, grounding and final-doc
 *       assembly. Reused by {@link SynthesisPipeline}.
 *   <li>{@link SynthesisPipeline} - the streaming-with-retry finalize call.
 * </ul>
 * Two LLM-backed operations remain here because they share the per-turn
 * interview {@link ChatClient} call shape:
 * <ul>
 *   <li>{@link #askNextQuestion}: returns the next clarifying question, or the
 *       literal token {@value InterviewQuestionPlanner#DONE_TOKEN} when the model
 *       (and the must-rule fallback) judge there's nothing high-value left to ask.
 *       Standards-driven: the LLM picks the next applicable rule / skill /
 *       checklist item and probes it. Project scan is intentionally NOT passed in
 *       - it just gives the model material to hallucinate around. Standards are
 *       expected to be pre-filtered by scope before reaching this method (see
 *       {@link StandardsSelector#selectRelevantStandards}) so per-turn dispatch
 *       can ship a focused menu instead of the full pack.
 *   <li>{@link #critiqueReadiness}: second-opinion critic over the Q&amp;A
 *       transcript, returns {@value InterviewQuestionPlanner#READY_TOKEN} or one
 *       follow-up question.
 * </ul>
 * Calls block on the streaming response - callers should run them on a
 * background thread (CompletableFuture.runAsync) so the TUI stays responsive.
 */
@Service
public class TaskAdvisorService {

    /** @see InterviewQuestionPlanner#DONE_TOKEN */
    public static final String DONE_TOKEN = InterviewQuestionPlanner.DONE_TOKEN;
    /** @see InterviewQuestionPlanner#READY_TOKEN */
    public static final String READY_TOKEN = InterviewQuestionPlanner.READY_TOKEN;

    private final ChatClient chatClient;
    private final PromptSelector promptSelector;
    private final SynthesisPipeline synthesisPipeline;
    private final Duration interviewTimeout;

    public TaskAdvisorService(
        ChatClient.Builder builder,
        PromptSelector promptSelector,
        SynthesisPipeline synthesisPipeline,
        LaunchpadTaskProperties taskProperties
    ) {
        this.chatClient = builder.build();
        this.promptSelector = promptSelector;
        this.synthesisPipeline = synthesisPipeline;
        this.interviewTimeout = taskProperties.interviewTimeout();
    }

    /** Delegates to {@link StandardsSelector#selectRelevantStandards}. Kept on
     *  this class so existing callers (LaunchpadRunner) don't churn. */
    public static RelevantStandards selectRelevantStandards(
        StackProfile stack,
        String taskDescription,
        List<TaskTurn> history,
        List<Rule> allRules,
        List<Skill> allSkills,
        List<Checklist> allChecklists
    ) {
        return StandardsSelector.selectRelevantStandards(
            stack, taskDescription, history, allRules, allSkills, allChecklists);
    }

    public String askNextQuestion(
        StackProfile stack,
        String taskDescription,
        List<TaskTurn> history,
        List<Rule> rules,
        List<Skill> skills,
        List<Checklist> checklists
    ) {
        var template = promptSelector.load(PromptSelector.Kind.TASK_INTERVIEW, stack);
        var parts = PromptFormatter.PromptParts.split(template);
        // Interview gets COMPACT one-line-per-item menus, not full descriptions.
        // Small local models lose the thread when handed 60+ rules with full
        // rationales - they start asking *about* the standards instead of *from*
        // them. Full text is reserved for the synthesise step.
        var response = chatClient.prompt()
            .system(parts.system())
            .user(u -> u.text(parts.user())
                .param("stack", PromptFormatter.formatStack(stack))
                .param("task", taskDescription)
                .param("history", PromptFormatter.formatHistory(history))
                .param("discovery_hint", InterviewQuestionPlanner.discoveryHintFor(taskDescription, history))
                .param("rules", PromptFormatter.formatRulesCompact(rules))
                .param("skills", PromptFormatter.formatSkillsCompact(skills))
                .param("checklists", PromptFormatter.formatChecklistsCompact(checklists)))
            .call()
            .content();
        var extracted = InterviewQuestionPlanner.extractQuestion(response);
        if (extracted.equals(DONE_TOKEN)) {
            // Override early DONE: small local models routinely return __DONE__
            // after a single round even when applicable [must]-severity rules
            // remain unaddressed. If we have uncovered must-rules and the
            // interview hasn't reached at least 3 rounds, synthesise a
            // standards-driven question deterministically from the next
            // uncovered rule instead of accepting DONE.
            if (history.size() < 3) {
                var taskTags = TaskClassifier.classifyTaskTags(taskDescription, history);
                var optedOut = TaskClassifier.detectOptedOutRules(rules, history);
                var forced = InterviewQuestionPlanner.pickNextUncoveredMustRule(
                    history, rules, stack, taskTags, optedOut);
                if (forced != null) return InterviewQuestionPlanner.synthesizeStandardsQuestion(forced);
            }
            return DONE_TOKEN;
        }
        // Defensive: small local models routinely re-ask the same question in
        // slightly different wording even after the user answered it. If the new
        // question overlaps heavily with anything in history, treat it as the
        // model having nothing new to ask and finalize.
        if (InterviewQuestionPlanner.isNearDuplicateOfPrior(extracted, history)) return DONE_TOKEN;
        return extracted;
    }

    /**
     * Second-opinion critic over the Q&amp;A transcript. Runs after the interview
     * model emits {@link #DONE_TOKEN} to judge whether the brief is actually
     * substantial enough to synthesise, or whether one more follow-up would
     * meaningfully improve it.
     * <p>
     * Returns {@link #READY_TOKEN} when the critic agrees the brief is ready;
     * otherwise returns one focused follow-up question (one sentence ending in
     * `?`). Near-duplicate questions of anything already in history collapse to
     * {@code READY_TOKEN} so the loop cannot oscillate on the same gap.
     * <p>
     * Same compact standards menus as {@link #askNextQuestion} - the critic
     * needs to see what is already covered. Uses the interview timeout (the
     * call is the same scale).
     */
    public String critiqueReadiness(
        StackProfile stack,
        String taskDescription,
        List<TaskTurn> history,
        List<Rule> rules,
        List<Skill> skills,
        List<Checklist> checklists
    ) {
        var template = promptSelector.load(PromptSelector.Kind.TASK_CRITIQUE, stack);
        var parts = PromptFormatter.PromptParts.split(template);
        var response = chatClient.prompt()
            .system(parts.system())
            .user(u -> u.text(parts.user())
                .param("stack", PromptFormatter.formatStack(stack))
                .param("task", taskDescription)
                .param("history", PromptFormatter.formatHistory(history))
                .param("rules", PromptFormatter.formatRulesCompact(rules))
                .param("skills", PromptFormatter.formatSkillsCompact(skills))
                .param("checklists", PromptFormatter.formatChecklistsCompact(checklists)))
            .call()
            .content();
        return InterviewQuestionPlanner.interpretCritiqueResponse(response, history);
    }

    /** Delegates to {@link SynthesisPipeline#synthesise}. */
    public SynthesiseResult synthesise(
        ProjectContext ctx,
        String taskDescription,
        List<TaskTurn> history,
        List<Rule> rules,
        List<Skill> skills,
        List<Checklist> checklists,
        Consumer<String> onChunk
    ) {
        return synthesisPipeline.synthesise(ctx, taskDescription, history, rules, skills, checklists, onChunk);
    }

    /** Exposed for the runner so it can surface a per-turn timeout deadline. */
    public Duration interviewTimeout() {
        return interviewTimeout;
    }
}
