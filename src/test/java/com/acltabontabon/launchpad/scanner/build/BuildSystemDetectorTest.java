package com.acltabontabon.launchpad.scanner.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildSystemDetectorTest {

    private final BuildSystemDetector detector = BuildSystemDetector.withDefaults();

    @Test
    void detectsMavenFromPom(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        var keyFiles = Map.of("pom.xml", "<project/>");

        var buildSystem = detector.detect(tmp, keyFiles);

        assertThat(buildSystem.name()).isEqualTo("Maven");
        assertThat(buildSystem.buildCommands()).containsExactly("./mvnw clean package", "./mvnw test");
    }

    @Test
    void detectsGradleFromBuildGradle(@TempDir Path tmp) throws Exception {
        var build = """
            dependencies {
              implementation 'org.springframework.boot:spring-boot-starter-web'
            }
            """;
        Files.writeString(tmp.resolve("build.gradle"), build);
        var keyFiles = Map.of("build.gradle", build);

        var buildSystem = detector.detect(tmp, keyFiles);

        assertThat(buildSystem.name()).isEqualTo("Gradle");
        assertThat(buildSystem.buildCommands()).containsExactly("./gradlew clean build", "./gradlew test");
        assertThat(buildSystem.dependencies(keyFiles))
            .extracting("name")
            .contains("org.springframework.boot:spring-boot-starter-web");
    }

    @Test
    void fallsBackToMavenWhenNoBuildFile(@TempDir Path tmp) {
        assertThat(detector.detect(tmp, Map.of()).name()).isEqualTo("Maven");
    }

    @Test
    void isProjectRootRecognisesMavenAndGradle(@TempDir Path tmp) throws Exception {
        assertThat(BuildSystemDetector.isProjectRoot(tmp)).isFalse();

        Files.writeString(tmp.resolve("build.gradle.kts"), "plugins { }\n");
        assertThat(BuildSystemDetector.isProjectRoot(tmp)).isTrue();
    }

    @Test
    void commandsForResolvesByToolName() {
        assertThat(BuildSystemDetector.commandsFor("Gradle"))
            .containsExactly("./gradlew clean build", "./gradlew test");
        assertThat(BuildSystemDetector.commandsFor("Maven"))
            .containsExactly("./mvnw clean package", "./mvnw test");
        assertThat(BuildSystemDetector.commandsFor("unknown"))
            .containsExactly("./mvnw clean package", "./mvnw test");
    }
}
