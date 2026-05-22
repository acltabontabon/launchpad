package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.template.ContextTarget;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Drives the LLM for the three generation kinds (summary, skills, cursor rules).
 * Each call:
 *   1. Picks a stack-specific prompt template via {@link PromptSelector}.
 *   2. Streams content to the caller.
 *   3. Validates the result with {@link OutputValidator}.
 *   4. On validation failure, retries once with a "follow the format" reminder.
 * The result carries any remaining warnings so the Review screen can surface them.
 */
@Service
public class ContextGeneratorService {

    private static final List<String> SUMMARY_HEADINGS = List.of("## Overview", "## Architecture");
    private static final List<String> SKILLS_HEADINGS = List.of("## Skill:");
    private static final List<String> CURSOR_HEADINGS = List.of("-");  // bullet list; weaker check

    private static final String RETRY_REMINDER = """

        IMPORTANT: your previous response was rejected for not following the required
        format. Output ONLY the requested sections with the EXACT headings shown
        above. Do not include preamble, apologies, or commentary.
        """;

    private final ChatClient chatClient;
    private final PromptSelector promptSelector;
    private final OutputValidator validator = new OutputValidator();

    public ContextGeneratorService(ChatClient.Builder builder, PromptSelector promptSelector) {
        this.chatClient = builder.build();
        this.promptSelector = promptSelector;
    }

    public GeneratedOutput generateProjectSummary(ProjectContext ctx, Consumer<String> onChunk) {
        var template = promptSelector.load(PromptSelector.Kind.SUMMARY, ctx.stack());
        return runWithValidation(template, ctx, SUMMARY_HEADINGS, onChunk);
    }

    public GeneratedOutput generateSkills(ProjectContext ctx, Consumer<String> onChunk) {
        var template = promptSelector.load(PromptSelector.Kind.SKILLS, ctx.stack());
        return runWithValidation(template, ctx, SKILLS_HEADINGS, onChunk);
    }

    public GeneratedOutput generateTargetSpecificContent(ProjectContext ctx, ContextTarget target, Consumer<String> onChunk) {
        return switch (target) {
            case CURSOR -> {
                var template = promptSelector.load(PromptSelector.Kind.CURSOR_RULES, ctx.stack());
                yield runWithValidation(template, ctx, CURSOR_HEADINGS, onChunk);
            }
            case CLAUDE -> generateSkills(ctx, onChunk);
        };
    }

    private GeneratedOutput runWithValidation(
        String template, ProjectContext ctx, List<String> requiredHeadings, Consumer<String> onChunk
    ) {
        String first = streamPrompt(template, ctx, onChunk);
        var warnings = validator.validate(first, ctx, requiredHeadings);
        boolean retriable = warnings.stream().anyMatch(w ->
            w.startsWith("empty") || w.startsWith("suspiciously short") || w.startsWith("missing required"));
        if (!retriable) {
            return warnings.isEmpty()
                ? GeneratedOutput.ok(first, false)
                : GeneratedOutput.withWarnings(first, warnings, false);
        }
        // One retry with reminder appended.
        String retried = streamPrompt(template + RETRY_REMINDER, ctx, onChunk);
        var retryWarnings = validator.validate(retried, ctx, requiredHeadings);
        // Keep whichever output is longer (heuristic: more content = more useful).
        String winning = retried.length() > first.length() ? retried : first;
        var winningWarnings = winning == retried ? retryWarnings : warnings;
        return winningWarnings.isEmpty()
            ? GeneratedOutput.ok(winning, true)
            : GeneratedOutput.withWarnings(winning, winningWarnings, true);
    }

    private String streamPrompt(String template, ProjectContext ctx, Consumer<String> onChunk) {
        var sb = new StringBuilder();
        chatClient.prompt()
            .user(u -> u.text(template).param("context", ctx.toPromptString()))
            .stream()
            .content()
            .doOnNext(chunk -> {
                sb.append(chunk);
                onChunk.accept(chunk);
            })
            .blockLast();
        return sb.toString();
    }
}
