package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Checklist(String id, String title, List<ChecklistItem> items, Scope scope) {
    public Checklist {
        if (scope == null) scope = Scope.empty();
    }
}
