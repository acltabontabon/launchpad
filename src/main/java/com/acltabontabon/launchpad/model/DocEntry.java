package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A curated documentation pointer. `purposeTag` mirrors the scanner's Purpose
 * classification (setup, architecture, ...), `anchor` is the path or fragment
 * to open the doc, `freshness` is an optional staleness note, and `gaps`
 * lists topics a reader would expect but the doc does not cover.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocEntry(
    String title,
    String purposeTag,
    String anchor,
    String freshness,
    List<String> gaps
) {
    public DocEntry {
        if (title == null) title = "";
        if (purposeTag == null) purposeTag = "";
        if (anchor == null) anchor = "";
        if (freshness == null) freshness = "";
        if (gaps == null) gaps = List.of();
    }
}
