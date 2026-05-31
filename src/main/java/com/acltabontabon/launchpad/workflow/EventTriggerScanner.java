package com.acltabontabon.launchpad.workflow;

import com.acltabontabon.launchpad.model.WorkflowType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, best-effort harvest of non-HTTP workflow triggers from JVM
 * source: scheduled jobs ({@code @Scheduled}) and message / event listeners
 * ({@code @KafkaListener}, {@code @RabbitListener}, {@code @JmsListener},
 * {@code @EventListener}, ...).
 * <p>
 * It reads each source file under a byte ceiling, finds trigger annotations,
 * and attaches the next method declaration so each trigger names the handler it
 * fires. No model is involved; every result is reproducible. The scan is
 * bounded by {@link #MAX_FILES} so a large monorepo cannot blow the budget.
 */
public final class EventTriggerScanner {

    /** A detected non-HTTP trigger: its kind, the annotation, the handler method, and the source file. */
    public record EventTrigger(WorkflowType type, String annotation, String method, String file) {}

    private static final int MAX_FILES = 300;
    private static final long MAX_FILE_BYTES = 24 * 1024;
    /** How many lines after an annotation to look for the handler method. */
    private static final int METHOD_LOOKAHEAD = 8;

    private static final Pattern TRIGGER_ANN = Pattern.compile(
        "@(Scheduled|KafkaListener|RabbitListener|JmsListener|EventListener"
            + "|TransactionalEventListener|StreamListener)\\b");

    // A plausible method declaration: optional modifiers, a return type token,
    // then `name(`. Modifiers are optional so package-private handlers like
    // `void onOrderPlaced(...)` match; requiring a return-type token before the
    // name avoids matching a bare method call such as `foo(x)`.
    private static final Pattern METHOD_DECL = Pattern.compile(
        "^(?:(?:public|protected|private|static|final|synchronized|default|abstract)\\s+)*"
            + "[\\w.<>\\[\\],?]+\\s+(\\w+)\\s*\\(");

    private EventTriggerScanner() {}

    /** Scan the given source files for trigger annotations. Never returns null. */
    public static List<EventTrigger> scan(Path projectRoot, List<String> sourceFiles) {
        if (projectRoot == null || sourceFiles == null || sourceFiles.isEmpty()) {
            return List.of();
        }

        var triggers = new ArrayList<EventTrigger>();
        int scanned = 0;
        for (var rel : sourceFiles) {
            if (scanned >= MAX_FILES) break;
            if (!isJvmSource(rel)) continue;

            List<String> lines = readLines(projectRoot.resolve(rel));
            if (lines == null) continue;
            scanned++;
            collectFromFile(rel, lines, triggers);
        }
        return triggers;
    }

    private static void collectFromFile(String file, List<String> lines, List<EventTrigger> out) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher ann = TRIGGER_ANN.matcher(lines.get(i));
            if (!ann.find()) continue;

            String annotation = ann.group(1);
            String method = findMethodAfter(lines, i);
            out.add(new EventTrigger(typeFor(annotation), annotation, method, file));
        }
    }

    /**
     * Look forward from the annotation line for the handler method name,
     * skipping blank lines and other annotations. Returns "" when none is found
     * within the lookahead window (the trigger is still recorded, evidence
     * points at the file).
     */
    private static String findMethodAfter(List<String> lines, int annotationLine) {
        int end = Math.min(lines.size(), annotationLine + 1 + METHOD_LOOKAHEAD);
        for (int j = annotationLine + 1; j < end; j++) {
            String trimmed = lines.get(j).strip();
            if (trimmed.isEmpty() || trimmed.startsWith("@") || trimmed.startsWith("//")) {
                continue;
            }
            Matcher m = METHOD_DECL.matcher(trimmed);
            if (m.find()) {
                return m.group(1);
            }
        }
        return "";
    }

    private static WorkflowType typeFor(String annotation) {
        return "Scheduled".equals(annotation) ? WorkflowType.SCHEDULED : WorkflowType.EVENT_DRIVEN;
    }

    private static List<String> readLines(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
                return null;
            }
            return Files.readAllLines(path);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isJvmSource(String relative) {
        return relative.endsWith(".java") || relative.endsWith(".kt");
    }
}
