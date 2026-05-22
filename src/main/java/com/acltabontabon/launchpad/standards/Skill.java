package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Skill(
    String id,
    String title,
    String trigger,
    List<String> steps,
    @JsonAlias("output_expectations") List<String> outputExpectations,
    String notes,
    Scope scope
) {
    public Skill {
        if (scope == null) scope = Scope.empty();
    }
}
