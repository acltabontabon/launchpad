package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.scanner.StackProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Picks a stack-specific prompt template from src/main/resources/prompts/
 * based on the detected framework. Falls back to "generic" when no
 * framework-specific template exists.
 */
@Component
public class PromptSelector {

    public enum Kind {
        SUMMARY("summary"),
        SKILLS("skills"),
        CURSOR_RULES("cursor-rules"),
        TASK_INTERVIEW("task/interview"),
        TASK_FINALIZE("task/finalize");

        final String dir;
        Kind(String dir) { this.dir = dir; }
    }

    public String load(Kind kind, StackProfile stack) {
        String slug = slugFor(stack);
        String primary = "prompts/" + kind.dir + "/" + slug + ".txt";
        try {
            return readClasspath(primary);
        } catch (IOException primaryMiss) {
            try {
                return readClasspath("prompts/" + kind.dir + "/generic.txt");
            } catch (IOException genericMiss) {
                throw new UncheckedIOException(
                    "Missing prompt template: tried " + primary + " and generic fallback",
                    genericMiss);
            }
        }
    }

    /** Returns the chosen template slug for telemetry / logging. */
    public String chosenSlug(Kind kind, StackProfile stack) {
        String slug = slugFor(stack);
        var primary = new ClassPathResource("prompts/" + kind.dir + "/" + slug + ".txt");
        return primary.exists() ? slug : "generic";
    }

    private static String slugFor(StackProfile stack) {
        if (stack == null || stack.framework() == null) return "generic";
        String f = stack.framework().toLowerCase();
        if (f.contains("spring")) return "spring";
        if (f.contains("next")) return "next";
        if (f.contains("django")) return "django";
        if (f.contains("fastapi")) return "fastapi";
        if (f.contains("rails")) return "rails";
        return "generic";
    }

    private static String readClasspath(String location) throws IOException {
        var resource = new ClassPathResource(location);
        if (!resource.exists()) throw new IOException("not found: " + location);
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
