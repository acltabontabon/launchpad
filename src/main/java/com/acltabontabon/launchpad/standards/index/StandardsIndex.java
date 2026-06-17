package com.acltabontabon.launchpad.standards.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Deterministic, machine-readable projection of a project's resolved standards
 * rules, persisted to {@code <projectRoot>/.launchpad/standards.index.json}
 * alongside {@code AGENTS.md}. It lets a runtime agent (or a context-mode index)
 * retrieve a single rule on demand instead of carrying the whole pack, and gives
 * audit findings a canonical registry to resolve their {@code ruleId} against.
 *
 * <p>Every field is derived from the deterministic standards resolution - no
 * model is involved - so the output is reproducible. {@code generatedAt} is the
 * only non-deterministic value and is deliberately excluded from every
 * {@link StandardsRuleEntry#contentHash()}.
 *
 * <p>Empty-state contract: when no rules resolve, the sidecar is still written
 * with {@code rules: []} and {@code source: null}, so consumers can tell
 * "no standards loaded" apart from "sidecar never generated".
 *
 * @param schemaVersion Bumped on any breaking change to this shape.
 * @param generatedAt   ISO-8601 generation timestamp; not part of any content hash.
 * @param source        Provenance of the resolved rule set; {@code null} when no rules resolve.
 * @param rules         One record per resolved rule, ordered by priority then id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StandardsIndex(
    int schemaVersion,
    String generatedAt,
    StandardsSource source,
    List<StandardsRuleEntry> rules
) {

    /** Current sidecar schema version. */
    public static final int SCHEMA_VERSION = 1;

    public StandardsIndex {
        if (rules == null) rules = List.of();
    }
}
