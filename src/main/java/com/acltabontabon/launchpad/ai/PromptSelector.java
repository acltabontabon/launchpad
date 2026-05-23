package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.scanner.StackProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Picks a prompt template for the requested kind + stack. Frameworks with a
 * <code>prompts/&lt;slug&gt;/base+facets/</code> tree on the classpath route
 * through {@link FacetPromptComposer}. Everything else falls back to the
 * per-kind generic template at {@code prompts/<kind>/generic.txt}.
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

    private final FacetPromptComposer composer;

    public PromptSelector(FacetPromptComposer composer) {
        this.composer = composer;
    }

    public String load(Kind kind, StackProfile stack) {
        if (isComposableKind(kind)) {
            String slug = slugFor(stack);
            List<String> facets = facetsFor(slug, stack);
            if (facets != null) {
                return composer.compose(kind, slug, facets);
            }
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
        String slug = slugFor(stack);
        if (isComposableKind(kind) && facetsFor(slug, stack) != null) {
            return slug;
        }
        var primary = new ClassPathResource("prompts/" + kind.dir + "/" + slug + ".txt");
        return primary.exists() ? slug : "generic";
    }

    /**
     * Returns the facet id list for a composable framework, or null if the
     * given slug has no composable tree (in which case the per-kind generic
     * template is used).
     */
    private static List<String> facetsFor(String slug, StackProfile stack) {
        if (stack == null) return null;
        return switch (slug) {
            case "spring" -> stack.springProfile() != null ? stack.springProfile().facets() : List.of();
            case "databricks" -> stack.databricksProfile() != null ? stack.databricksProfile().facets() : List.of();
            default -> null;
        };
    }

    private static boolean isComposableKind(Kind kind) {
        return kind == Kind.SUMMARY || kind == Kind.SKILLS || kind == Kind.RULES;
    }

    private static String slugFor(StackProfile stack) {
        if (stack == null || stack.framework() == null) return "generic";
        String f = stack.framework().toLowerCase();
        if (f.contains("spring")) return "spring";
        if (f.contains("databricks")) return "databricks";
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
