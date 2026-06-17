package com.acltabontabon.launchpad.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A directed edge in the project-model graph. {@code from} and {@code to} are
 * {@link ProjectNode#id() node ids}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectEdge(String from, String to, EdgeType type) {
}
