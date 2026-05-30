package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Stable identity and provenance header for a virtualized project model.
 * `generatedAt` is an ISO-8601 timestamp supplied by the caller, `packVersion`
 * is the standards-pack version the model was built against (or ""), and
 * `contentHash` is a stable digest of the deterministic inputs so consumers
 * can detect staleness and cross-reference snapshots.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectIdentity(
    String name,
    String rootPath,
    String primaryStack,
    String generatedAt,
    String packVersion,
    String contentHash
) {
    public ProjectIdentity {
        if (name == null) name = "";
        if (rootPath == null) rootPath = "";
        if (primaryStack == null) primaryStack = "";
        if (generatedAt == null) generatedAt = "";
        if (packVersion == null) packVersion = "";
        if (contentHash == null) contentHash = "";
    }
}
