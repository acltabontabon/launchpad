package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Internal binding for standards-pack.yml at the root of a pack. The presence of
 * this file in a source directory is what makes the directory a resolvable pack.
 *
 * <p>{@code schemaVersion} is the manifest <em>format</em> version (an int), bumped
 * only when the pack shape changes in a breaking way - distinct from {@code version},
 * which is the semver of the team's content. It is required: {@link StandardsLoader}
 * rejects a manifest whose {@code schemaVersion} is missing or outside the supported
 * range, so a future format change never loads silently against an old Launchpad.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record StandardsPackManifest(
    Integer schemaVersion,
    String id,
    String version,
    String name,
    String description,
    List<String> maintainers,
    Includes includes,
    Map<String, String> adapters,
    List<String> projections
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Includes(
        List<String> rules,
        List<String> skills,
        List<String> checklists
    ) {}
}
