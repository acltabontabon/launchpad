package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A logical subsystem of the project (named to avoid clashing with
 * java.lang.System). `id` is stable for cross-referencing from workflows and
 * risks; `owningPackages` and `entryPoints` are deterministic structural
 * facts; `responsibility` is a short synthesized description.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SystemComponent(
    String id,
    String name,
    String responsibility,
    List<String> entryPoints,
    List<String> owningPackages,
    List<String> externalDeps,
    Confidence confidence,
    List<Evidence> evidence
) {
    public SystemComponent {
        if (id == null) id = "";
        if (name == null) name = "";
        if (responsibility == null) responsibility = "";
        if (entryPoints == null) entryPoints = List.of();
        if (owningPackages == null) owningPackages = List.of();
        if (externalDeps == null) externalDeps = List.of();
        if (confidence == null) confidence = Confidence.DETERMINISTIC;
        if (evidence == null) evidence = List.of();
    }
}
