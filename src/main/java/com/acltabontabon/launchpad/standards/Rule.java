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
    Integer priority,
    Check check
) {
    public Rule {
        if (scope == null) scope = Scope.empty();
    }

    /** True when this rule carries audit machinery; false for doc-only rules. */
    public boolean isAuditable() {
        return check != null && check.kind() != null && !check.kind().isBlank();
    }

    /** Priority for sorting; lower = more important. Defaults to 99 if not set in YAML. */
    public int priorityValue() {
        return priority == null ? 99 : priority;
    }
}
