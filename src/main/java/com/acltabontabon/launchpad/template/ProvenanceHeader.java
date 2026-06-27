package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.standards.index.StandardsSource;
import com.acltabontabon.launchpad.support.LaunchpadVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.Nullable;

/**
 * Machine-readable lineage stamp prepended to every generated context file
 * ({@code AGENTS.md} and the {@code .ai/*.md} companions) so a reader can answer
 * "what produced this?" without consulting git history.
 *
 * <p>Rendered as a single-line HTML comment: a stable {@code launchpad:provenance}
 * marker token followed by a compact JSON payload, e.g.
 * <pre>{@code
 * <!-- launchpad:provenance {"schemaVersion":1,"launchpadVersion":"0.6.0",...} -->
 * }</pre>
 * HTML-comment form is tolerated by every markdown renderer and matches the
 * existing {@link MergeMarkers} convention. The marker token makes the line
 * greppable; the JSON payload makes it parseable.
 *
 * @param schemaVersion    Bumped on any breaking change to this payload shape.
 * @param launchpadVersion Running Launchpad build version (or {@code "dev"}).
 * @param generatedAt      ISO-8601 generation timestamp.
 * @param standards        Resolved standards-pack provenance; {@code null} when no pack resolved.
 * @param aiModel          The model that synthesized sections, or {@code "deterministic-only"}.
 */
public record ProvenanceHeader(
    int schemaVersion,
    String launchpadVersion,
    String generatedAt,
    @Nullable Standards standards,
    String aiModel
) {

    /** Current provenance payload schema version. */
    public static final int SCHEMA_VERSION = 1;

    /** Stable, greppable marker token that introduces the JSON payload. */
    public static final String MARKER = "launchpad:provenance";

    /** Sentinel {@code aiModel} value when no LLM synthesis ran. */
    public static final String DETERMINISTIC_ONLY = "deterministic-only";

    /** Standards-pack provenance, mirroring {@link StandardsSource}. */
    public record Standards(String pack, String version, String origin) {
        static Standards from(@Nullable StandardsSource source) {
            return source == null ? null
                : new Standards(source.pack(), source.version(), source.origin());
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    /**
     * Builds a header from the resolved generation inputs. {@code aiModel} is the
     * configured model name when AI synthesis was enabled, else
     * {@link #DETERMINISTIC_ONLY}.
     */
    public static ProvenanceHeader of(String generatedAt,
                                      @Nullable StandardsSource source,
                                      String aiModel) {
        return new ProvenanceHeader(
            SCHEMA_VERSION,
            LaunchpadVersion.current(),
            generatedAt,
            Standards.from(source),
            aiModel);
    }

    /** The single-line HTML comment, with a trailing newline, ready to prepend. */
    public String render() {
        try {
            return "<!-- " + MARKER + " " + JSON.writeValueAsString(this) + " -->\n";
        } catch (JsonProcessingException e) {
            // The payload is a flat record of strings/ints - serialization cannot
            // realistically fail, but never emit a half-written marker if it does.
            throw new IllegalStateException("Failed to render provenance header", e);
        }
    }
}
