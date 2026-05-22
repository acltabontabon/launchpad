package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Rule(
    String id,
    String title,
    String severity,
    @JsonAlias("content") String description,
    String rationale,
    Scope scope,
    Integer priority
) {
    public Rule {
        if (scope == null) scope = Scope.empty();
    }

    /** Priority for sorting; lower = more important. Defaults to 99 if not set in YAML. */
    public int priorityValue() {
        return priority == null ? 99 : priority;
    }
}
