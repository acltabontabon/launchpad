package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.scanner.StackProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Picks a prompt template for the requested kind. Composable kinds
 * (SKILLS / RULES) delegate to whichever {@link FrameworkPromptStrategy}
 * applies to the project; non-composable kinds (task interview / finalize)
 * read their per-kind template directly.
 * <p>
 * Adding a new supported framework is a matter of registering a new
 * {@link FrameworkPromptStrategy} bean and dropping a {@code prompts/<slug>/}
 * tree on the classpath - this class does not change. The strategy list is
 * injected as a Spring-managed bean list, so order is determined by
 * {@link org.springframework.core.annotation.Order} on the strategies if any
 * tie-breaking is ever needed.
 * <p>
 * Kinds are deliberately named after the output <em>format</em>
 * (SKILLS / RULES) rather than the consuming vendor. Today the canonical
 * primary file is the vendor-neutral {@code AGENTS.md}; any vendor-specific
 * adaptation lives at the output-adapter layer, not in the prompt content.
 */
@Component
public class PromptSelector {

    public enum Kind {
        SKILLS("skills"),
        RULES("rules"),
        TASK_INTERVIEW("task/interview"),
        TASK_CRITIQUE("task/critique"),
        TASK_FINALIZE("task/finalize");

        final String dir;
        Kind(String dir) { this.dir = dir; }
    }

    private final FacetPromptComposer composer;
    private final List<FrameworkPromptStrategy> strategies;

    public PromptSelector(FacetPromptComposer composer, List<FrameworkPromptStrategy> strategies) {
        this.composer = composer;
        this.strategies = List.copyOf(strategies);
    }

    public String load(Kind kind, StackProfile stack) {
        if (isComposableKind(kind)) {
            var strategy = resolveStrategy(stack);
            return composer.compose(kind, strategy.slug(), strategy.facetsFor(stack));
        }
        String primary = "prompts/" + kind.dir + "/generic.txt";
        try {
            return readClasspath(primary);
        } catch (IOException miss) {
            throw new UncheckedIOException("Missing prompt template: " + primary, miss);
        }
    }

    /** Returns the chosen template slug for telemetry / logging. */
    public String chosenSlug(Kind kind, StackProfile stack) {
        if (isComposableKind(kind)) return resolveStrategy(stack).slug();
        return "generic";
    }

    private FrameworkPromptStrategy resolveStrategy(StackProfile stack) {
        for (var strategy : strategies) {
            if (strategy.appliesTo(stack)) return strategy;
        }
        throw new IllegalStateException(
            "No FrameworkPromptStrategy applies to stack "
                + (stack == null ? "<null>" : stack.displayName())
                + ". ProjectSupportDetector should have rejected this project before generation.");
    }

    private static boolean isComposableKind(Kind kind) {
        return kind == Kind.SKILLS || kind == Kind.RULES;
    }

    private static String readClasspath(String location) throws IOException {
        var resource = new ClassPathResource(location);
        if (!resource.exists()) throw new IOException("not found: " + location);
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
