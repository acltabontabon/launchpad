package com.acltabontabon.launchpad.standards.index;

import com.acltabontabon.launchpad.standards.Scope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One machine-readable standards rule record in the {@link StandardsIndex}
 * sidecar. Deliberately self-contained: a runtime agent that retrieves a single
 * entry by {@link #id()} has the rule's full normative text, applicability, and
 * provenance without carrying the rest of the pack.
 *
 * <p>The {@link #id()} is the join key shared with audit findings -
 * {@code standards.index.json rules[*].id == audit.sarif.json ruleId == audit.md rule id}.
 *
 * @param id          Stable rule id, authored in the standards pack.
 * @param title       Human-readable rule name.
 * @param severity    Rule severity (e.g. {@code CRITICAL}, {@code HIGH}); may be {@code null}.
 * @param scope       Applicability scope (languages, frameworks, tools, tasks, tags).
 * @param description The rule's normative text.
 * @param rationale   Why the rule exists; may be {@code null}.
 * @param source      Provenance of the rule (mirrors the index-level source by design,
 *                    so a single retrieved record stays self-contained).
 * @param auditable   True when the rule carries audit-check machinery.
 * @param checkKind   The audit check kind ({@code forbid-pattern}, {@code forbid-import},
 *                    {@code llm}) when auditable; {@code null} for doc-only rules.
 * @param priority    Effective sort priority (lower = more important; defaults to 99).
 * @param contentHash SHA-256 over the rule's content (excludes {@code generatedAt}) for drift detection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StandardsRuleEntry(
    String id,
    String title,
    String severity,
    Scope scope,
    String description,
    String rationale,
    StandardsSource source,
    boolean auditable,
    String checkKind,
    int priority,
    String contentHash
) {

    public StandardsRuleEntry {
        if (scope == null) scope = Scope.empty();
    }
}
