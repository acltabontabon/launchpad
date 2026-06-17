package com.acltabontabon.launchpad.standards.index;

import com.acltabontabon.launchpad.standards.Scope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One machine-readable standards skill record in the {@link StandardsIndex}
 * sidecar. Like {@link StandardsRuleEntry}, it is self-contained: retrieving a
 * single entry by {@link #id()} yields the skill's trigger, steps, and provenance
 * without carrying the rest of the pack.
 *
 * @param id                 Stable skill id (authored, or a deterministic slug fallback).
 * @param title              Human-readable skill name.
 * @param trigger            When the skill applies; may be {@code null}.
 * @param steps              Ordered steps; may be {@code null}.
 * @param outputExpectations Ordered output expectations; may be {@code null}.
 * @param notes              Free-form notes; may be {@code null}.
 * @param scope              Applicability scope (languages, frameworks, tools, tasks, tags).
 * @param source             Provenance (mirrors the index-level source by design).
 * @param contentHash        SHA-256 over the skill's content (excludes {@code generatedAt}) for drift detection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StandardsSkillEntry(
    String id,
    String title,
    String trigger,
    List<String> steps,
    List<String> outputExpectations,
    String notes,
    Scope scope,
    StandardsSource source,
    String contentHash
) {

    public StandardsSkillEntry {
        if (scope == null) scope = Scope.empty();
    }
}
