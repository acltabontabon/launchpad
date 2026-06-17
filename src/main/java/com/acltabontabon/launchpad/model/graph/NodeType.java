package com.acltabontabon.launchpad.model.graph;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * The kind of node in the project-model graph. Serialized as its lowercase
 * name (e.g. {@code "component"}) so the sidecar stays language-neutral.
 */
public enum NodeType {
    PROJECT,
    PACKAGE,
    COMPONENT,
    ENDPOINT,
    ENTRYPOINT,
    DEPENDENCY;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
