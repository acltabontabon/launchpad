package com.acltabontabon.launchpad.standards.index;

import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Scope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One machine-readable standards checklist record in the {@link StandardsIndex}
 * sidecar. Self-contained like {@link StandardsRuleEntry}: it embeds its items
 * (each with a stable id) so a consumer retrieving one checklist by {@link #id()}
 * has the whole checklist without parsing the pack.
 *
 * @param id          Stable checklist id (authored, or a deterministic slug fallback).
 * @param title       Human-readable checklist name.
 * @param scope       Applicability scope (languages, frameworks, tools, tasks, tags).
 * @param items       Ordered checklist items, each carrying a stable id; may be empty.
 * @param source      Provenance (mirrors the index-level source by design).
 * @param contentHash SHA-256 over the checklist's content, including its items
 *                    (excludes {@code generatedAt}), for drift detection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StandardsChecklistEntry(
    String id,
    String title,
    Scope scope,
    List<ChecklistItem> items,
    StandardsSource source,
    String contentHash
) {

    public StandardsChecklistEntry {
        if (scope == null) scope = Scope.empty();
        if (items == null) items = List.of();
    }
}
