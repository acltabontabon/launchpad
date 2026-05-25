package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Internal binding for standards-pack.yml at the root of a pack.
 * The presence of this file in a source directory switches StandardsLoader
 * from flat-file mode to manifest-aware mode.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record StandardsPackManifest(
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
