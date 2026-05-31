package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Curated map of the project's documentation, plus a synthesized list of
 * notable gaps across the whole corpus (topics a senior engineer would expect
 * to find documented but cannot).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentationMap(
    List<DocEntry> entries,
    List<String> corpusGaps
) {
    public DocumentationMap {
        if (entries == null) entries = List.of();
        if (corpusGaps == null) corpusGaps = List.of();
    }

    public static DocumentationMap empty() {
        return new DocumentationMap(List.of(), List.of());
    }
}
