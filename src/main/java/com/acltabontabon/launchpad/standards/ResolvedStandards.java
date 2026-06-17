package com.acltabontabon.launchpad.standards;

import com.acltabontabon.launchpad.standards.index.StandardsSource;
import java.util.List;

/**
 * The outcome of resolving a standards pack from a single resolution decision:
 * the rules, skills, and checklists plus the {@link StandardsSource} describing
 * the selected pack. Produced by {@link StandardsLoader#loadResolvedStandards}.
 *
 * <p>Every record carries a stable, non-blank {@code id} (see
 * {@link StandardsIdentity}), so downstream consumers - the sidecar, audit,
 * MCP tools - can key off identity without text matching.
 *
 * <p>{@code source} is the pack-level selected standards-pack source used for the
 * resolved projection, not a per-kind source. It is the same source resolution
 * already used by rules (the rules' winning source dir); every emitted sidecar
 * entry mirrors it so a single retrieved record stays self-contained.
 *
 * @param rules      Resolved rules; empty when no rules resolve.
 * @param skills     Resolved skills; empty when no skills resolve.
 * @param checklists Resolved checklists; empty when no checklists resolve.
 * @param source     Provenance of the resolved pack; {@code null} when no rules resolve.
 */
public record ResolvedStandards(
    List<Rule> rules,
    List<Skill> skills,
    List<Checklist> checklists,
    StandardsSource source
) {

    public ResolvedStandards {
        if (rules == null) rules = List.of();
        if (skills == null) skills = List.of();
        if (checklists == null) checklists = List.of();
    }
}
