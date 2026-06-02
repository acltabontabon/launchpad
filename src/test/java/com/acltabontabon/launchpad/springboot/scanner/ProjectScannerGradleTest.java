package com.acltabontabon.launchpad.springboot.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end check that a Spring Boot Gradle project scans correctly: the
 * stack reports Gradle, dependencies are extracted from {@code build.gradle},
 * and the framework is Spring Boot - all without a {@code pom.xml}.
 */
class ProjectScannerGradleTest {

    private final ProjectScanner scanner = ProjectScanner.forTesting();

    @Test
    void scansGradleProjectAsSpringBootGradle(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"), """
            plugins {
              id 'java'
              id 'org.springframework.boot' version '3.3.0'
            }
            dependencies {
              implementation 'org.springframework.boot:spring-boot-starter-web'
            }
            """);

        var ctx = scanner.scan(tmp.toString(), msg -> { });

        assertThat(ctx.stack().language()).isEqualTo("Java");
        assertThat(ctx.stack().buildTool()).isEqualTo("Gradle");
        assertThat(ctx.stack().framework()).isEqualTo("Spring Boot");
        assertThat(ctx.dependencies())
            .extracting("name")
            .contains("org.springframework.boot:spring-boot-starter-web");
    }
}
