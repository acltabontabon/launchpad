package com.acltabontabon.launchpad.springboot.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringBootGradleSupportSignalTest {

    private final SpringBootGradleSupportSignal signal = new SpringBootGradleSupportSignal();

    @Test
    void matchesGroovyPluginBlock(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"),
            "plugins { id 'org.springframework.boot' version '3.3.0' }\n");

        assertThat(signal.evaluate(tmp)).isPresent();
    }

    @Test
    void matchesKotlinPluginBlock(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle.kts"),
            "plugins { id(\"org.springframework.boot\") version \"3.3.0\" }\n");

        assertThat(signal.evaluate(tmp)).isPresent();
    }

    @Test
    void matchesStarterDependency(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"), """
            dependencies {
              implementation 'org.springframework.boot:spring-boot-starter'
            }
            """);

        assertThat(signal.evaluate(tmp)).isPresent();
    }

    @Test
    void matchesLegacyBuildscriptPlugin(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"), """
            buildscript {
              dependencies {
                classpath 'org.springframework.boot:spring-boot-gradle-plugin:2.7.0'
              }
            }
            """);

        assertThat(signal.evaluate(tmp)).isPresent();
    }

    @Test
    void abstainsWithoutSpringSignal(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"), """
            plugins { id 'java' }
            dependencies {
              implementation 'com.google.guava:guava:33.0.0-jre'
            }
            """);

        assertThat(signal.evaluate(tmp)).isEmpty();
    }

    @Test
    void abstainsWhenNoBuildFile(@TempDir Path tmp) {
        assertThat(signal.evaluate(tmp)).isEmpty();
    }
}
