package com.acltabontabon.launchpad.task;

import com.acltabontabon.launchpad.ai.PromptSelector;
import com.acltabontabon.launchpad.config.LaunchpadTaskProperties;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Hybrid synthesise pipeline: streams a marker-delimited LLM response (Goal /
 * Acceptance / Out-of-scope only), then hands it to {@link MarkdownPostProcessor}
 * for parsing, grounding and final-document assembly. Owns the per-chunk
 * streaming timeout and the single-shot retry policy that fires when the
 * validator flags a structural failure. Java assembles Constraints + Standards
 * consulted deterministically from the filtered standards pack so the model
 * cannot hallucinate constraints or placeholder text into the output.
 */
@Service
public class SynthesisPipeline {

    private static final String FINALIZE_RETRY_REMINDER = """

        FORMAT VIOLATION: your previous response did not produce the three
        marker-delimited sections. Output ONLY:
        ===GOAL===
        <one prose paragraph>
        ===ACCEPTANCE===
        - <bullet>
        ===OUT_OF_SCOPE===
        - <bullet> or (none)
        Use ONLY the user's words. Nothing else.
        """;

    private final ChatClient chatClient;
    private final PromptSelector promptSelector;
    private final TaskOutputValidator validator = new TaskOutputValidator();
    private final Duration finalizeTimeout;

    public SynthesisPipeline(
        ChatClient.Builder builder,
        PromptSelector promptSelector,
        LaunchpadTaskProperties taskProperties
    ) {
        this.chatClient = builder.build();
        this.promptSelector = promptSelector;
        this.finalizeTimeout = taskProperties.finalizeTimeout();
    }

    /**
     * Streams the model response with a per-chunk timeout so a stalled daemon
     * surfaces fast; emits the assembled doc through {@code onChunk} once parsing
     * + grounding + assembly complete. On validator failure, retries once with a
     * stricter reminder; keeps the retry only if it actually has fewer warnings.
     */
    public SynthesiseResult synthesise(
        ProjectContext ctx,
        String taskDescription,
        List<TaskTurn> history,
        List<Rule> rules,
        List<Skill> skills,
        List<Checklist> checklists,
        Consumer<String> onChunk
    ) {
        // Standards are expected pre-filtered by the caller. Re-filtering here is
        // cheap and protects against callers that pass an unfiltered pack
        // (legacy / test setups). selectRelevantStandards is idempotent: a list
        // that already matches the scope passes through unchanged.
        var filtered = StandardsSelector.selectRelevantStandards(
            ctx == null ? null : ctx.stack(), taskDescription, history, rules, skills, checklists);

        var template = promptSelector.load(PromptSelector.Kind.TASK_FINALIZE, ctx == null ? null : ctx.stack());
        var parts = PromptFormatter.PromptParts.split(template);
        if (onChunk != null) onChunk.accept("");  // signal: synthesis starting

        var raw = streamSynthesise(parts.system(), parts.user(), taskDescription, history, ctx);
        var doc = buildDocument(taskDescription, history, raw, filtered);
        var warnings = validator.validate(doc);

        // Single retry on hard structural failure. We treat "missing required
        // section: ## Goal" or "synthesised prompt was empty" as retriable -
        // the model fundamentally failed to produce the marker shape. Cosmetic
        // warnings (quote-only bullets, placeholders) are NOT retriable; the
        // user sees them and can decide.
        if (isRetriable(warnings)) {
            var rawRetry = streamSynthesise(
                parts.system() + FINALIZE_RETRY_REMINDER,
                parts.user(),
                taskDescription, history, ctx);
            var docRetry = buildDocument(taskDescription, history, rawRetry, filtered);
            var warningsRetry = validator.validate(docRetry);
            if (warningsRetry.size() < warnings.size()) {
                doc = docRetry;
                warnings = warningsRetry;
            }
        }

        if (onChunk != null) onChunk.accept(doc);
        return new SynthesiseResult(doc, warnings);
    }

    private String streamSynthesise(String system, String userTemplate,
            String taskDescription, List<TaskTurn> history, ProjectContext ctx) {
        var sb = new StringBuilder();
        chatClient.prompt()
            .system(system)
            .user(u -> u.text(userTemplate)
                .param("task", taskDescription)
                .param("stack", PromptFormatter.formatStack(ctx == null ? null : ctx.stack()))
                .param("history", PromptFormatter.formatHistory(history)))
            .stream()
            .content()
            // Per-chunk inactivity timeout: if the stream stops emitting tokens
            // for longer than finalizeTimeout, surface a TimeoutException instead
            // of blocking the TUI thread forever.
            .timeout(finalizeTimeout)
            .doOnNext(sb::append)
            .blockLast();
        return sb.toString();
    }

    /**
     * Runs the post-processing chain in its load-bearing order:
     * parseMarkerSections -&gt; separateGoalProseFromBullets -&gt; groundAcceptance
     * -&gt; groundOutOfScope -&gt; assembleFinalMarkdown. Each stage assumes its
     * predecessor ran; reordering changes the user-visible output.
     */
    private String buildDocument(String taskDescription, List<TaskTurn> history,
            String raw, RelevantStandards filtered) {
        var sections = MarkdownPostProcessor.parseMarkerSections(raw == null ? "" : raw);
        sections = MarkdownPostProcessor.separateGoalProseFromBullets(sections);
        sections = MarkdownPostProcessor.groundAcceptance(sections, taskDescription, history);
        var optedOutRuleIds = TaskClassifier.detectOptedOutRules(filtered.rules(), history);
        sections = MarkdownPostProcessor.groundOutOfScope(sections, history, optedOutRuleIds, filtered.rules());

        return MarkdownPostProcessor.assembleFinalMarkdown(taskDescription, sections,
            filtered.rules(), filtered.skills(), filtered.checklists());
    }

    private static boolean isRetriable(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) return false;
        for (var w : warnings) {
            if (w.startsWith("missing required section")) return true;
            if (w.contains("empty")) return true;
            // Goal-too-short is a substance failure the retry can plausibly
            // fix: the FINALIZE_RETRY_REMINDER pushes the model toward emitting
            // the marker sections cleanly, which is the path that produces a
            // meaningful Goal paragraph.
            if (w.contains("too short to be actionable")) return true;
        }
        return false;
    }
}
