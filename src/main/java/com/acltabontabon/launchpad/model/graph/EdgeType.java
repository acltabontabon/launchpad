package com.acltabontabon.launchpad.model.graph;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A directed relationship between two nodes in the project-model graph.
 * Serialized as its kebab form (e.g. {@code "depends-on"}).
 */
public enum EdgeType {
    /** A container holds the target (project -&gt; package, package -&gt; component). */
    CONTAINS("contains"),
    /** A controller component exposes an HTTP endpoint. */
    EXPOSES("exposes"),
    /** A component implements an interface component. */
    IMPLEMENTS("implements"),
    /** The project depends on an external dependency. */
    DEPENDS_ON("depends-on");

    private final String json;

    EdgeType(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
