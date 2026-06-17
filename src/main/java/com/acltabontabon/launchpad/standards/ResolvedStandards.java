package com.acltabontabon.launchpad.standards;

import com.acltabontabon.launchpad.standards.index.StandardsSource;
import java.util.List;

/**
 * The outcome of resolving standards rules from a single resolution decision:
 * the rules themselves plus the {@link StandardsSource} describing the winning
 * source directory. Produced by {@link StandardsLoader#loadResolvedRules}.
 *
 * <p>Pairing the two guarantees the sidecar invariant: the emitted {@code source}
 * always describes exactly the emitted {@code rules}, never a separately-resolved
 * origin that could drift.
 *
 * @param rules  Resolved rules; empty when no standards resolve.
 * @param source Provenance of the winning source; {@code null} when no rules resolve.
 */
public record ResolvedStandards(List<Rule> rules, StandardsSource source) {

    public ResolvedStandards {
        if (rules == null) rules = List.of();
    }
}
