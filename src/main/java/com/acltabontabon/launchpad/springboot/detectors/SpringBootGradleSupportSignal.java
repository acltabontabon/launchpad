package com.acltabontabon.launchpad.springboot.detectors;

import com.acltabontabon.launchpad.scanner.ProjectSupportSignal;
import com.acltabontabon.launchpad.springboot.gradle.GradleBuildModel;
import com.acltabontabon.launchpad.springboot.gradle.GradleBuildParser;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Recognises Spring Boot Java + Gradle projects by parsing
 * {@code build.gradle} / {@code build.gradle.kts} through
 * {@link GradleBuildParser} and looking for structured Spring Boot signals:
 * the {@code org.springframework.boot} plugin id, any
 * {@code org.springframework.boot:spring-boot-starter} dependency, or the
 * legacy {@code spring-boot-gradle-plugin} on the buildscript classpath.
 * <p>
 * The Gradle counterpart of {@link SpringBootMavenSupportSignal}; like it,
 * detection is deliberately strict and structural rather than a fuzzy text
 * match on the raw build script.
 */
@Component
public class SpringBootGradleSupportSignal implements ProjectSupportSignal {

    private static final String FRAMEWORK_LABEL = "Spring Boot Java + Gradle";
    private static final String SPRING_BOOT_PLUGIN_ID = "org.springframework.boot";
    private static final String SPRING_BOOT_STARTER = "org.springframework.boot:spring-boot-starter";
    private static final String LEGACY_GRADLE_PLUGIN = "spring-boot-gradle-plugin";

    private final GradleBuildParser gradleBuildParser;

    public SpringBootGradleSupportSignal(GradleBuildParser gradleBuildParser) {
        this.gradleBuildParser = gradleBuildParser;
    }

    public SpringBootGradleSupportSignal() {
        this(new GradleBuildParser());
    }

    @Override
    public Optional<Match> evaluate(Path projectRoot) {
        if (projectRoot == null) return Optional.empty();
        return gradleBuildParser.parseAtRoot(projectRoot)
            .filter(SpringBootGradleSupportSignal::hasSpringSignal)
            .map(model -> new Match(FRAMEWORK_LABEL));
    }

    private static boolean hasSpringSignal(GradleBuildModel model) {
        if (model.pluginIds().contains(SPRING_BOOT_PLUGIN_ID)) {
            return true;
        }
        boolean springStarter = model.dependencies().stream()
            .map(d -> d.name() == null ? "" : d.name())
            .anyMatch(name -> name.startsWith(SPRING_BOOT_STARTER));
        if (springStarter) return true;
        return model.buildscriptClasspath().stream()
            .anyMatch(c -> c.contains(LEGACY_GRADLE_PLUGIN));
    }
}
