package com.acltabontabon.launchpad.springboot.maven;

import com.acltabontabon.launchpad.scanner.Dependency;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Structured view of a {@code pom.xml}. Produced by {@link MavenModelParser}
 * and consumed by every downstream phase that needs Maven facts (dependency
 * extraction, project-support detection, future BOM analysis).
 *
 * <p>Empty / blank string fields indicate "absent" rather than null - callers
 * can equality-check without null guards. {@code dependencies} and
 * {@code plugins} are never null; they are empty when the pom declares none
 * or when parsing failed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MavenModel(
    String parentGroupId,
    String parentArtifactId,
    String parentVersion,
    List<Dependency> dependencies,
    List<PluginCoordinate> plugins
) {

    public MavenModel {
        if (parentGroupId == null) parentGroupId = "";
        if (parentArtifactId == null) parentArtifactId = "";
        if (parentVersion == null) parentVersion = "";
        if (dependencies == null) dependencies = List.of();
        if (plugins == null) plugins = List.of();
    }

    public static MavenModel empty() {
        return new MavenModel("", "", "", List.of(), List.of());
    }
}
