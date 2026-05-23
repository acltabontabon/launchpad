package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.scanner.StackProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Picks a prompt template for the requested kind + stack. For Spring projects
 * on the three generation kinds (summary, skills, rules) it delegates to
 * {@link SpringPromptComposer} which assembles a base template plus sub-stack
 * facet sections. Everything else falls back to the per-kind generic template
 * under {@code prompts/<kind>/generic.txt}.
 * <p>
 * Kinds are deliberately named after the output <em>format</em>
 * (SUMMARY / SKILLS / RULES) rather than the consuming vendor. A bulleted-rules
 * format can be emitted into Cursor's {@code .cursorrules}, an Aider
 * {@code CONVENTIONS.md}, or any other tool's equivalent without rewriting the
 * prompt - the vendor-specific decision lives at the output-adapter layer,
 * not in the prompt content.
 */
@Component
public class PromptSelector {

    public enum Kind {
        SUMMARY("summary"),
        SKILLS("skills"),
        RULES("rules"),
        TASK_INTERVIEW("task/interview"),
        TASK_FINALIZE("task/finalize");

        final String dir;
        Kind(String dir) { this.dir = dir; }
    }

    private final SpringPromptComposer springComposer;

    public PromptSelector(SpringPromptComposer springComposer) {
        this.springComposer = springComposer;
    }

    public String load(Kind kind, StackProfile stack) {
        if (isComposableKind(kind) && "spring".equals(slugFor(stack))) {
            return springComposer.compose(kind, stack == null ? null : stack.springProfile());
        }
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
        if (isComposableKind(kind) && "spring".equals(slugFor(stack))) {
            return "spring";
        }
        String slug = slugFor(stack);
        var primary = new ClassPathResource("prompts/" + kind.dir + "/" + slug + ".txt");
        return primary.exists() ? slug : "generic";
    }

    private static boolean isComposableKind(Kind kind) {
        return kind == Kind.SUMMARY || kind == Kind.SKILLS || kind == Kind.RULES;
    }

    private static String slugFor(StackProfile stack) {
        if (stack == null || stack.framework() == null) return "generic";
        String f = stack.framework().toLowerCase();
        if (f.contains("spring")) return "spring";
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
