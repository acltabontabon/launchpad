package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Optional applicability scope on rules, skills, and checklists. Authored in YAML
 * by tech leads and consulted by {@code TaskAdvisorService} to decide which items
 * apply to a given task. All lists are interpreted as "no entries = no restriction";
 * non-empty lists must overlap with the task's project framework / language / tags
 * for the item to apply.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Scope(
    List<String> languages,
    List<String> frameworks,
    List<String> tools,
    List<String> tasks,
    List<String> tags
) {
    public Scope {
        if (languages == null) languages = List.of();
        if (frameworks == null) frameworks = List.of();
        if (tools == null) tools = List.of();
        if (tasks == null) tasks = List.of();
        if (tags == null) tags = List.of();
    }

    public static Scope empty() {
        return new Scope(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
