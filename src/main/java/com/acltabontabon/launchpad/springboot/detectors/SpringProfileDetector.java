package com.acltabontabon.launchpad.springboot.detectors;

import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.springboot.runtime.SpringProfile;
import java.util.List;

/**
 * Builds a SpringProfile from an already-extracted dependency list plus
 * a small set of file-presence signals captured during the project walk.
 * Pure function over its inputs - no IO, easy to unit-test.
 * <p>
 * Dependency names from pom.xml come as "groupId:artifactId"
 * (e.g. "org.springframework.boot:spring-boot-starter-web"). Detection is
 * substring-based so a future Gradle parser that emits "spring-boot-starter-web"
 * alone would still match.
 */
public final class SpringProfileDetector {

    /**
     * Signals derived from the file tree that the dependency list alone
     * cannot express. Kept as a record so adding a new signal does not
     * change the detector's method signature.
     * <p>
     * All starter-library signals are positive (something only a library would
     * ship). Negative signals like "no main class" are deliberately avoided -
     * they break on multi-module projects, libraries with test-scope main
     * classes, and similar edge cases that would silently misclassify apps.
     */
    public record Signals(
        boolean hasAutoConfigurationImports,
        boolean hasSpringFactories,
        boolean hasAutoConfigurationAnnotation
    ) {
        public static final Signals NONE = new Signals(false, false, false);
    }

    public SpringProfile detect(List<Dependency> dependencies) {
        return detect(dependencies, Signals.NONE);
    }

    public SpringProfile detect(List<Dependency> dependencies, Signals signals) {
        boolean web = false, webflux = false;
        boolean jpa = false, jdbc = false, r2dbc = false;
        boolean springAi = false, springCloud = false, springSecurity = false;
        boolean kafka = false, rabbit = false;
        boolean nativeImage = false;

        for (var d : dependencies) {
            String n = d.name() == null ? "" : d.name().toLowerCase();
            if (n.isEmpty()) continue;

            if (n.contains("spring-boot-starter-web") && !n.contains("webflux")) web = true;
            if (n.contains("spring-webmvc")) web = true;
            if (n.contains("spring-boot-starter-webflux") || n.contains("spring-webflux")) webflux = true;

            if (n.contains("spring-boot-starter-data-jpa") || n.contains("spring-data-jpa")) jpa = true;
            if (n.contains("spring-boot-starter-jdbc") || n.contains("spring-jdbc")) jdbc = true;
            if (n.contains("spring-boot-starter-data-r2dbc") || n.contains("spring-data-r2dbc")) r2dbc = true;

            if (n.contains("org.springframework.ai") || n.contains("spring-ai")) springAi = true;
            if (n.contains("org.springframework.cloud") || n.contains("spring-cloud")) springCloud = true;
            if (n.contains("spring-boot-starter-security") || n.contains("spring-security")) springSecurity = true;

            if (n.contains("spring-kafka")) kafka = true;
            if (n.contains("spring-boot-starter-amqp") || n.contains("spring-rabbit")) rabbit = true;

            if (n.contains("org.graalvm.buildtools") || n.contains("native-maven-plugin")
                || n.contains("native-gradle-plugin")) nativeImage = true;
        }

        boolean starterLibrary = signals.hasAutoConfigurationImports()
            || signals.hasSpringFactories()
            || signals.hasAutoConfigurationAnnotation();

        return new SpringProfile(web, webflux, jpa, jdbc, r2dbc, springAi, springCloud,
            springSecurity, kafka, rabbit, nativeImage, starterLibrary);
    }
}
