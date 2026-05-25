package com.acltabontabon.launchpad.springboot.maven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A Maven build profile detected from `pom.xml`. Used to render a `## Build
 * profiles` section in the generated AGENTS.md when a project ships multiple
 * profiles (PGO variants, native-image flavours, environment overrides, ...).
 *
 * @param id          The profile `<id>`. Always non-blank.
 * @param activation  Short human label of the `<activation>` block (e.g.
 *                    "default-on", "active by default", "JDK 21", "OS Linux",
 *                    "property env=prod") or empty when the profile is opt-in
 *                    via `-P`.
 * @param keyFlags    Short list of distinctive flags captured from the
 *                    profile body: `argLine` / `jvmArgs` tokens, native-image
 *                    plugin flags, PGO flags, skip flags. Bounded; best-effort.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MavenProfile(String id, String activation, List<String> keyFlags) {

    public MavenProfile {
        if (id == null) id = "";
        if (activation == null) activation = "";
        if (keyFlags == null) keyFlags = List.of();
    }
}
