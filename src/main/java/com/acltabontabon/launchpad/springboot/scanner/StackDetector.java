package com.acltabontabon.launchpad.springboot.scanner;

import com.acltabontabon.launchpad.scanner.ProjectSupportDetector;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.scanner.build.BuildSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Produces the Spring framework label from the project's build-file text and
 * takes the build-tool label from the resolved {@link BuildSystem}. The
 * language is always Java - any project that reaches this point has already
 * cleared {@link ProjectSupportDetector} - but the build tool may be Maven or
 * Gradle, so it is read from the strategy rather than assumed.
 */
public final class StackDetector {

    public StackProfile detect(Path root, Map<String, String> keyFileContents, BuildSystem buildSystem) {
        return new StackProfile("Java", buildSystem.name(),
            detectSpringFramework(buildFileText(keyFileContents)), List.of());
    }

    /** Concatenate the known build files so framework detection works for either tool. */
    private static String buildFileText(Map<String, String> keyFileContents) {
        return keyFileContents.getOrDefault("pom.xml", "")
            + "\n" + keyFileContents.getOrDefault("build.gradle", "")
            + "\n" + keyFileContents.getOrDefault("build.gradle.kts", "");
    }

    private static String detectSpringFramework(String buildContent) {
        if (buildContent.contains("spring-boot")) return "Spring Boot";
        if (buildContent.contains("org.springframework")) return "Spring";
        return "Spring Boot";
    }
}
