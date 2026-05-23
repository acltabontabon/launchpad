package com.acltabontabon.launchpad.audit;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Rule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Asks the local model a yes/no question per file selected by the rule's
 * {@code targets} (or {@code includes} globs). Uses Spring AI structured-output
 * via {@code .entity(LlmCheckResult.class)} so we get parsed JSON, not free-form
 * markdown - this avoids the model-drift problems that {@code OutputValidator}
 * was built to defend against on the generation path.
 * <p>
 * Selector shorthands:
 * <ul>
 *   <li>{@code controllers} - any file whose basename ends with {@code Controller.*}</li>
 *   <li>{@code services} - basename ends with {@code Service.*}</li>
 *   <li>{@code configs} - basename ends with {@code Config.*}, {@code Configuration.*}, or path contains {@code /config/}</li>
 *   <li>{@code files-matching} - fall back to {@code check.includes} globs (no shorthand)</li>
 * </ul>
 * The matched file set is capped per rule to keep token cost predictable; the
 * cap is intentionally low because LLM checks are expensive relative to the
 * deterministic checkers and exist only for rules deterministic kinds cannot express.
 */
@Component
public class LlmChecker implements RuleChecker {

    private static final Logger log = LoggerFactory.getLogger(LlmChecker.class);
    private static final int MAX_FILES_PER_RULE = 20;
    private static final long MAX_FILE_BYTES = 80_000L;

    private final ChatClient chatClient;

    public LlmChecker(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public String kind() {
        return "llm";
    }

    @Override
    public List<Finding> check(Rule rule, ProjectContext ctx, Path projectRoot) {
        var check = rule.check();
        if (check == null || check.question() == null || check.question().isBlank()) {
            return List.of();
        }
        var selected = selectFiles(rule, ctx);
        var findings = new ArrayList<Finding>();
        for (var relativePath : selected) {
            var content = safeRead(projectRoot.resolve(relativePath));
            if (content == null) continue;
            var result = askModel(rule, relativePath, content);
            if (result != null && result.violated()) {
                findings.add(Finding.at(
                    rule.id(),
                    rule.severity(),
                    rule.title(),
                    relativePath,
                    result.line() == null ? 0 : result.line(),
                    result.reason() == null ? "Violation reported by local model." : result.reason(),
                    ""
                ));
            }
        }
        return findings;
    }

    LlmCheckResult askModel(Rule rule, String relativePath, String content) {
        var prompt = """
            You are auditing a single source file for compliance with a standard.

            STANDARD: %s
            DESCRIPTION: %s
            QUESTION: %s

            Respond with JSON only. Set "violated" to true ONLY when the file
            clearly violates the standard. Set "line" to the offending line
            number if you can identify one, else 0. Keep "reason" under 25 words.

            FILE: %s
            CONTENT:
            ```
            %s
            ```
            """.formatted(
                rule.title() == null ? rule.id() : rule.title(),
                rule.description() == null ? "" : rule.description(),
                rule.check().question(),
                relativePath,
                content
            );
        try {
            return chatClient.prompt()
                .user(prompt)
                .call()
                .entity(LlmCheckResult.class);
        } catch (RuntimeException firstFailure) {
            log.debug("LLM check parse failure for rule {} on {} - retrying once",
                rule.id(), relativePath);
            try {
                return chatClient.prompt()
                    .user(prompt + "\n\nReturn ONLY a JSON object with the fields violated, reason, line.")
                    .call()
                    .entity(LlmCheckResult.class);
            } catch (RuntimeException secondFailure) {
                log.warn("LLM check failed twice for rule {} on {} - skipping ({})",
                    rule.id(), relativePath, secondFailure.getMessage());
                return null;
            }
        }
    }

    List<String> selectFiles(Rule rule, ProjectContext ctx) {
        return LlmCheckerSelector.select(rule, ctx.sourceFiles(), MAX_FILES_PER_RULE);
    }

    private static String safeRead(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
                return null;
            }
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }
}
