package com.acltabontabon.launchpad.springboot.gradle;

import com.acltabontabon.launchpad.scanner.Dependency;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Structured view of a {@code build.gradle} / {@code build.gradle.kts} - the
 * Gradle analogue of {@link com.acltabontabon.launchpad.springboot.maven.MavenModel}.
 *
 * <p>{@code pluginIds} are the applied plugin ids (e.g.
 * {@code org.springframework.boot}); {@code dependencies} reuse the shared
 * {@link Dependency} record; {@code buildscriptClasspath} carries the legacy
 * {@code buildscript { dependencies { classpath ... } }} coordinates (the
 * pre-plugins-DSL way of applying the Spring Boot plugin).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GradleBuildModel(
    List<String> pluginIds,
    List<Dependency> dependencies,
    List<String> buildscriptClasspath
) {

    public GradleBuildModel {
        if (pluginIds == null) pluginIds = List.of();
        if (dependencies == null) dependencies = List.of();
        if (buildscriptClasspath == null) buildscriptClasspath = List.of();
    }

    public static GradleBuildModel empty() {
        return new GradleBuildModel(List.of(), List.of(), List.of());
    }
}
