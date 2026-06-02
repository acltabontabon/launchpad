package com.acltabontabon.launchpad.scanner.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves which {@link BuildSystem} a project uses by consulting the
 * registered strategies and taking the first match - the same first-match
 * design as {@link com.acltabontabon.launchpad.scanner.ProjectSupportDetector}.
 * Maven is the fallback so behaviour is unchanged for any project that
 * cleared the gate without an explicit build-tool signal.
 *
 * <p>Order is significant: callers should register the more specific strategy
 * first. With Maven and Gradle both present (a polyglot repo), the first in
 * the list wins.
 */
@Component
public final class BuildSystemDetector {

    private final List<BuildSystem> buildSystems;
    private final BuildSystem fallback;

    public BuildSystemDetector(List<BuildSystem> buildSystems) {
        this.buildSystems = List.copyOf(buildSystems);
        this.fallback = buildSystems.stream()
            .filter(bs -> "Maven".equals(bs.name()))
            .findFirst()
            .orElseGet(MavenBuildSystem::new);
    }

    /** Plain-construction factory mirroring the scanner's news-up style. */
    public static BuildSystemDetector withDefaults() {
        return new BuildSystemDetector(List.of(new MavenBuildSystem(), new GradleBuildSystem()));
    }

    /**
     * Cheap pre-filter for "does this directory look like a buildable project
     * root" - a recognised build file (Maven or Gradle) sits directly in it.
     * Used by project discovery to decide whether to run the full support
     * gate, so the recognised-file list lives next to the build strategies.
     */
    public static boolean isProjectRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
            || Files.isRegularFile(dir.resolve("build.gradle"))
            || Files.isRegularFile(dir.resolve("build.gradle.kts"));
    }

    public BuildSystem detect(Path root, Map<String, String> keyFiles) {
        for (var buildSystem : buildSystems) {
            if (buildSystem.matches(root, keyFiles)) {
                return buildSystem;
            }
        }
        return fallback;
    }

    /**
     * Canonical build commands for a build-tool label (e.g. {@code "Gradle"}),
     * resolved from the default strategies so the commands are defined once on
     * the {@link BuildSystem} implementations. Falls back to Maven's commands
     * for an unknown label.
     */
    public static List<String> commandsFor(String buildTool) {
        var defaults = List.<BuildSystem>of(new MavenBuildSystem(), new GradleBuildSystem());
        return defaults.stream()
            .filter(bs -> bs.name().equalsIgnoreCase(buildTool))
            .findFirst()
            .orElse(defaults.get(0))
            .buildCommands();
    }
}
