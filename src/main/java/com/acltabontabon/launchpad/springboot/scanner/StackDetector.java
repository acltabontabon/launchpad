package com.acltabontabon.launchpad.springboot.scanner;

import com.acltabontabon.launchpad.scanner.ProjectSupportDetector;
import com.acltabontabon.launchpad.scanner.StackProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Produces the Spring framework label from a Maven {@code pom.xml}'s text.
 * Always returns {@code Java / Maven} - any project that reaches this point
 * has already cleared {@link ProjectSupportDetector}, so the language and
 * build tool are constants.
 */
public final class StackDetector {

    public StackProfile detect(Path root, Map<String, String> keyFileContents) {
        var pom = keyFileContents.getOrDefault("pom.xml", "");
        return new StackProfile("Java", "Maven", detectSpringFramework(pom), List.of());
    }

    private static String detectSpringFramework(String pomContent) {
        if (pomContent.contains("spring-boot")) return "Spring Boot";
        if (pomContent.contains("org.springframework")) return "Spring";
        return "Spring Boot";
    }
}
