package com.acltabontabon.launchpad.springboot.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleBuildParserTest {

    private final GradleBuildParser parser = new GradleBuildParser();

    @Test
    void parsesPluginIdsFromGroovyBlock() {
        var model = parser.parse("""
            plugins {
              id 'java'
              id 'org.springframework.boot' version '3.3.0'
            }
            """);

        assertThat(model.pluginIds()).contains("java", "org.springframework.boot");
    }

    @Test
    void parsesPluginIdsFromKotlinBlock() {
        var model = parser.parse("""
            plugins {
              id("org.springframework.boot") version "3.3.0"
            }
            """);

        assertThat(model.pluginIds()).contains("org.springframework.boot");
    }

    @Test
    void parsesDependencyCoordinates() {
        var model = parser.parse("""
            dependencies {
              implementation 'org.springframework.boot:spring-boot-starter-web'
            }
            """);

        assertThat(model.dependencies())
            .extracting("name")
            .contains("org.springframework.boot:spring-boot-starter-web");
    }

    @Test
    void parsesBuildscriptClasspath() {
        var model = parser.parse("""
            buildscript {
              dependencies {
                classpath 'org.springframework.boot:spring-boot-gradle-plugin:2.7.0'
              }
            }
            """);

        assertThat(model.buildscriptClasspath())
            .contains("org.springframework.boot:spring-boot-gradle-plugin:2.7.0");
    }

    @Test
    void ignoresCommentedLines() {
        var model = parser.parse("""
            plugins {
              // id 'org.springframework.boot' version '3.3.0'
              /* id 'org.springframework.boot' */
              id 'java'
            }
            """);

        assertThat(model.pluginIds()).containsExactly("java");
    }

    @Test
    void blankReturnsEmpty() {
        assertThat(parser.parse("  ")).isEqualTo(GradleBuildModel.empty());
    }

    @Test
    void parseAtRootPrefersBuildGradleOverKts(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"),
            "plugins { id 'org.springframework.boot' }\n");
        Files.writeString(tmp.resolve("build.gradle.kts"),
            "plugins { id(\"java\") }\n");

        var model = parser.parseAtRoot(tmp);

        assertThat(model).isPresent();
        assertThat(model.get().pluginIds()).contains("org.springframework.boot");
    }

    @Test
    void parseAtRootMissingReturnsEmpty(@TempDir Path tmp) {
        assertThat(parser.parseAtRoot(tmp)).isEmpty();
    }
}
