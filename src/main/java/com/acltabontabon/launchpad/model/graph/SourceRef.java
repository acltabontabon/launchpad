package com.acltabontabon.launchpad.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Pointer to the source a node was derived from: a project-relative path and
 * an optional 1-based line range. {@code startLine} / {@code endLine} are null
 * for nodes that have no single source declaration (packages are directories,
 * dependencies live in a manifest), so the schema is uniform across node types.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SourceRef(String path, Integer startLine, Integer endLine) {

    /** A path-only pointer with no line range (directories, manifests). */
    public static SourceRef of(String path) {
        return new SourceRef(path, null, null);
    }

    /** A pointer spanning a line range; lines &lt;= 0 collapse to null. */
    public static SourceRef of(String path, int startLine, int endLine) {
        return new SourceRef(path, startLine > 0 ? startLine : null, endLine > 0 ? endLine : null);
    }
}
