package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A provenance pointer backing a synthesized claim in the model. `source` is a
 * file path (or other deterministic origin such as "pom.xml" or "scan"),
 * `line` is an optional 1-based line number (null when not applicable), and
 * `detail` is a short note describing what the evidence shows.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Evidence(
    String source,
    Integer line,
    String detail
) {
    public Evidence {
        if (source == null) source = "";
        if (detail == null) detail = "";
    }

    public static Evidence of(String source, String detail) {
        return new Evidence(source, null, detail);
    }

    public static Evidence at(String source, int line, String detail) {
        return new Evidence(source, line, detail);
    }
}
