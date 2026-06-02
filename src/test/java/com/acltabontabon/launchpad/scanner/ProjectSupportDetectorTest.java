package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSupportDetectorTest {

    private final ProjectSupportDetector detector = new ProjectSupportDetector(java.util.List.of(
        new com.acltabontabon.launchpad.springboot.detectors.SpringBootMavenSupportSignal(),
        new com.acltabontabon.launchpad.springboot.detectors.SpringBootGradleSupportSignal()));

    @Test
    void supportedWhenParentIsSpringBootStarterParent(@TempDir Path tmp) throws Exception {
        writePom(tmp, """
            <project>
              <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>3.3.0</version>
              </parent>
              <artifactId>demo</artifactId>
            </project>
            """);

        assertThat(detector.detect(tmp).isSupported()).isTrue();
    }

    @Test
    void supportedWhenSpringBootDependencyIsDeclared(@TempDir Path tmp) throws Exception {
        writePom(tmp, """
            <project>
              <artifactId>demo</artifactId>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        assertThat(detector.detect(tmp).isSupported()).isTrue();
    }

    @Test
    void supportedWhenSpringBootMavenPluginIsConfigured(@TempDir Path tmp) throws Exception {
        writePom(tmp, """
            <project>
              <artifactId>demo</artifactId>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

        assertThat(detector.detect(tmp).isSupported()).isTrue();
    }

    @Test
    void unsupportedWhenPomDeclaresNoSpringSignal(@TempDir Path tmp) throws Exception {
        writePom(tmp, """
            <project>
              <artifactId>plain-maven</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>33.0.0-jre</version>
                </dependency>
              </dependencies>
            </project>
            """);

        var result = detector.detect(tmp);

        assertThat(result.isSupported()).isFalse();
        assertThat(result.reason()).contains("Maven or Gradle");
    }

    @Test
    void unsupportedWhenNoPomAtRoot(@TempDir Path tmp) {
        var result = detector.detect(tmp);

        assertThat(result.isSupported()).isFalse();
        assertThat(result.reason()).contains("Maven or Gradle");
    }

    @Test
    void supportedForGradleGroovyPluginBlock(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"),
            "plugins { id 'org.springframework.boot' version '3.3.0' }\n");

        assertThat(detector.detect(tmp).isSupported()).isTrue();
    }

    @Test
    void supportedForGradleKotlinPluginBlock(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle.kts"),
            "plugins { id(\"org.springframework.boot\") version \"3.3.0\" }\n");

        assertThat(detector.detect(tmp).isSupported()).isTrue();
    }

    @Test
    void supportedForGradleStarterDependency(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"), """
            dependencies {
              implementation 'org.springframework.boot:spring-boot-starter-web'
            }
            """);

        assertThat(detector.detect(tmp).isSupported()).isTrue();
    }

    @Test
    void unsupportedForGradleWithoutSpringSignal(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"), """
            plugins { id 'java' }
            dependencies {
              implementation 'com.google.guava:guava:33.0.0-jre'
            }
            """);

        assertThat(detector.detect(tmp).isSupported()).isFalse();
    }

    @Test
    void unsupportedWhenMalformedPom(@TempDir Path tmp) throws Exception {
        writePom(tmp, "<project><dependencies><dependency>");

        assertThat(detector.detect(tmp).isSupported()).isFalse();
    }

    @Test
    void unsupportedWhenProjectRootIsNull() {
        var result = detector.detect(null);

        assertThat(result.isSupported()).isFalse();
        assertThat(result.reason()).contains("Maven or Gradle");
    }

    private static void writePom(Path root, String content) throws Exception {
        Files.writeString(root.resolve("pom.xml"), content);
    }
}
