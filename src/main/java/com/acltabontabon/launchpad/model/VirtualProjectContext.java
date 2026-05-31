package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The virtualized project model: a synthesized, queryable answer to "what would
 * a senior engineer need to know to become productive in this repository in
 * five minutes?".
 * <p>
 * This sits on top of the deterministic scan
 * ({@code com.acltabontabon.launchpad.scanner.ProjectContext}) - the scan is
 * the raw substrate, this aggregate is the synthesized understanding. It is
 * built by {@link ProjectContextAssembler}, serialized to
 * {@code .launchpad/project-context.json}, and projected into AGENTS.md and
 * the .ai/ companion files.
 * <p>
 * Structural fields are deterministic; narrative and inference fields carry a
 * {@link Confidence} and backing {@link Evidence} so consumers can tell
 * ground truth from suggestion.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VirtualProjectContext(
    ProjectIdentity identity,
    Architecture architecture,
    List<SystemComponent> systems,
    List<Workflow> workflows,
    StandardsProfile standards,
    Operations operations,
    DocumentationMap documentation,
    List<Risk> risks
) {
    public VirtualProjectContext {
        if (identity == null) identity = new ProjectIdentity("", "", "", "", "", "");
        if (architecture == null) architecture = Architecture.empty();
        if (systems == null) systems = List.of();
        if (workflows == null) workflows = List.of();
        if (standards == null) standards = StandardsProfile.empty();
        if (operations == null) operations = Operations.empty();
        if (documentation == null) documentation = DocumentationMap.empty();
        if (risks == null) risks = List.of();
    }
}
