package com.acltabontabon.launchpad.scanner.build;

import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.springboot.gradle.GradleBuildParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Gradle {@link BuildSystem}. Recognises a {@code build.gradle} /
 * {@code build.gradle.kts} at the root and reads declared dependencies from it
 * through {@link GradleBuildParser} - the same structured Gradle entry point
 * the support gate uses. Dependencies are deduped by {@code name@scope} while
 * preserving declaration order, matching the Maven extractor's contract.
 */
@Component
public class GradleBuildSystem implements BuildSystem {

    private static final String BUILD_GRADLE = "build.gradle";
    private static final String BUILD_GRADLE_KTS = "build.gradle.kts";

    private final GradleBuildParser gradleBuildParser;

    public GradleBuildSystem(GradleBuildParser gradleBuildParser) {
        this.gradleBuildParser = gradleBuildParser;
    }

    public GradleBuildSystem() {
        this(new GradleBuildParser());
    }

    @Override
    public String name() {
        return "Gradle";
    }

    @Override
    public boolean matches(Path root, Map<String, String> keyFiles) {
        return keyFiles.containsKey(BUILD_GRADLE)
            || keyFiles.containsKey(BUILD_GRADLE_KTS)
            || Files.isRegularFile(root.resolve(BUILD_GRADLE))
            || Files.isRegularFile(root.resolve(BUILD_GRADLE_KTS));
    }

    @Override
    public List<String> buildCommands() {
        return List.of("./gradlew clean build", "./gradlew test");
    }

    @Override
    public List<Dependency> dependencies(Map<String, String> keyFiles) {
        var build = keyFiles.getOrDefault(BUILD_GRADLE,
            keyFiles.getOrDefault(BUILD_GRADLE_KTS, ""));
        if (build == null || build.isBlank()) return new ArrayList<>();
        var deps = new LinkedHashMap<String, Dependency>();
        for (var dep : gradleBuildParser.parse(build).dependencies()) {
            deps.putIfAbsent(key(dep), dep);
        }
        return new ArrayList<>(deps.values());
    }

    private static String key(Dependency d) {
        return d.name() + "@" + (d.scope() == null ? "" : d.scope());
    }
}
