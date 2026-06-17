package com.acltabontabon.launchpad.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Deterministic, machine-readable projection of a project's structure as a
 * graph of {@link ProjectNode}s and {@link ProjectEdge}s. Persisted to
 * {@code <projectRoot>/.launchpad/project.model.json} alongside
 * {@code AGENTS.md} so an agent (or a context-mode index) can traverse the
 * project semantically instead of re-reading files.
 * <p>
 * Every field here is derived from the deterministic scan - no model is
 * involved - so the output is reproducible. {@code generatedAt} is the only
 * non-deterministic value and is deliberately excluded from every node's
 * {@link ProjectNode#contentHash()}.
 *
 * @param schemaVersion Bumped on any breaking change to this shape.
 * @param generatedAt   ISO-8601 generation timestamp.
 * @param projectName   Project name, mirrored from the scan.
 * @param rootPath      Absolute project root path.
 * @param nodes         All graph nodes; each {@link ProjectNode#id()} is unique.
 * @param edges         All graph edges, referencing node ids.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectModel(
    int schemaVersion,
    String generatedAt,
    String projectName,
    String rootPath,
    List<ProjectNode> nodes,
    List<ProjectEdge> edges
) {

    /** Current sidecar schema version. */
    public static final int SCHEMA_VERSION = 1;

    public ProjectModel {
        if (nodes == null) nodes = List.of();
        if (edges == null) edges = List.of();
    }
}
