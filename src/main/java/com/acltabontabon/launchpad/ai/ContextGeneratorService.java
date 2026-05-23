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
    // Loose substring check: matches "## Skill: <name>" but also "## Skill <name>",
    // "## Skill\n", or any other variation where the model drops the colon. The
    // downstream ContextTemplateEngine handles further parsing.
    private static final List<String> SKILLS_HEADINGS = List.of("## Skill");
    private static final List<String> CURSOR_HEADINGS = List.of("-");  // bullet list; weaker check

    private static final String RETRY_REMINDER = """

        IMPORTANT: your previous response was rejected for not following the required
        format. Output ONLY the requested sections with the EXACT headings shown
        above. Do not include preamble, apologies, or commentary.
        """;

    /**
     * Cap on how many file paths we list to the model. Each file averages ~50
     * chars including the leading "- " and "\n"; 120 lines is ~6KB which fits
     * comfortably even alongside an 8KB context block on a 4K-token model.
     */
    private static final int MAX_GROUNDING_FILES = 120;

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
            return finalize(first, warnings, ctx, false);
        }
        // One retry with reminder appended.
        String retried = streamPrompt(template + RETRY_REMINDER, ctx, onChunk);
        var retryWarnings = validator.validate(retried, ctx, requiredHeadings);
        String winning = retried.length() > first.length() ? retried : first;
        var winningWarnings = winning == retried ? retryWarnings : warnings;
        return finalize(winning, winningWarnings, ctx, true);
    }

    /**
     * Strips hallucinated file references from the winning content before it
     * ships. Only user-actionable warnings are surfaced. "missing required
     * section" drives the retry decision internally but is dropped from the
     * user-facing list, since the assembled output still embeds the content
     * under a labeled section either way. Hallucination stripping is silent
     * for the same reason: the cleanup already happened, nothing for the
     * user to do.
     */
    private GeneratedOutput finalize(String content, List<String> warnings, ProjectContext ctx, boolean retried) {
        var clean = validator.cleanHallucinations(content, ctx);
        var userVisible = new java.util.ArrayList<String>();
        for (var w : warnings) {
            if (w.startsWith("missing required")) continue;
            userVisible.add(w);
        }
        return userVisible.isEmpty()
            ? GeneratedOutput.ok(clean.content(), retried)
            : GeneratedOutput.withWarnings(clean.content(), userVisible, retried);
    }

    private String streamPrompt(String template, ProjectContext ctx, Consumer<String> onChunk) {
        // Append a file-list grounding block to every prompt. Small models stuck
        // on framework boilerplate routinely invent files (LoanApplicationController,
        // UserService, ...). Giving them an explicit closed set + an anti-invention
        // rule dramatically reduces those hallucinations.
        var grounded = template + buildFileGrounding(ctx);
        var sb = new StringBuilder();
        chatClient.prompt()
            .user(u -> u.text(grounded).param("context", ctx.toPromptString()))
            .stream()
            .content()
            .doOnNext(chunk -> {
                sb.append(chunk);
                onChunk.accept(chunk);
            })
            .blockLast();
        return sb.toString();
    }

    /**
     * Builds the file-list grounding suffix appended to every prompt. The model
     * sees this as part of the user message after the original template body.
     */
    static String buildFileGrounding(ProjectContext ctx) {
        var files = ctx.sourceFiles();
        if (files == null || files.isEmpty()) return "";

        int cap = Math.min(files.size(), MAX_GROUNDING_FILES);
        var sb = new StringBuilder();
        sb.append("\n\n---\n");
        sb.append("FILES IN THIS PROJECT (the ONLY files you may reference in backticks):\n");
        for (int i = 0; i < cap; i++) {
            sb.append("- ").append(files.get(i)).append('\n');
        }
        if (files.size() > cap) {
            sb.append("- ... and ").append(files.size() - cap).append(" more files\n");
        }
        sb.append("\n");
        sb.append("STRICT RULES for file references in your output:\n");
        sb.append("- When citing a file in backticks, use ONLY paths from the list above.\n");
        sb.append("- Do NOT invent class names like `LoanApplicationController.java`, `UserService.java`,\n");
        sb.append("  `OrderRepository.java`, or any other Spring-Boot-shaped boilerplate that does not\n");
        sb.append("  appear in the list. If you cannot find a real file for a point, use the package name\n");
        sb.append("  (e.g. `com.example.foo`) or a generic descriptor (\"the controller layer\") instead.\n");
        sb.append("- Bullet entries that name a file MUST cite a real file from the list.\n");
        return sb.toString();
    }
}
