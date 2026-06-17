package com.acltabontabon.launchpad.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One node in the project-model graph.
 *
 * @param id           Stable, globally unique identifier of the form
 *                     {@code "<type>/<slug>"} (e.g. {@code "component/src-main-...-userscontroller-java"}).
 * @param type         The kind of node.
 * @param name         Human-readable label.
 * @param source       Where the node was derived from; null when not source-backed.
 * @param attributes   Type-specific key/value detail (kind, method, version, ...).
 * @param contentHash  SHA-256 over the node's canonical fields. Excludes the
 *                     generation timestamp so it is stable across re-runs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectNode(
    String id,
    NodeType type,
    String name,
    SourceRef source,
    Map<String, String> attributes,
    String contentHash
) {

    public ProjectNode {
        if (attributes == null) attributes = Map.of();
    }
}
