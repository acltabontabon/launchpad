package com.acltabontabon.launchpad.springboot.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenModelParserTest {

    private final MavenModelParser parser = new MavenModelParser();

    @Test
    void parsesParentCoordinates() {
        String pom = """
            <project>
              <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>3.3.0</version>
              </parent>
              <artifactId>demo</artifactId>
            </project>
            """;

        var model = parser.parse(pom);

        assertThat(model.parentGroupId()).isEqualTo("org.springframework.boot");
        assertThat(model.parentArtifactId()).isEqualTo("spring-boot-starter-parent");
        assertThat(model.parentVersion()).isEqualTo("3.3.0");
    }

    @Test
    void parsesDependenciesWithGroupAndScope() {
        String pom = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """;

        var model = parser.parse(pom);

        assertThat(model.dependencies()).extracting("name", "scope").containsExactly(
            tuple("org.springframework.boot:spring-boot-starter-web", "runtime"),
            tuple("org.junit.jupiter:junit-jupiter", "test"));
    }

    @Test
    void parsesBuildPluginsIncludingSpringBootPlugin() {
        String pom = """
            <project>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                  </plugin>
                  <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

        var model = parser.parse(pom);

        assertThat(model.plugins()).extracting("artifactId").containsExactlyInAnyOrder(
            "spring-boot-maven-plugin", "maven-surefire-plugin");
    }

    @Test
    void malformedPomReturnsEmptyModel() {
        var model = parser.parse("<project><dependencies><dependency>");

        assertThat(model.parentArtifactId()).isEmpty();
        assertThat(model.dependencies()).isEmpty();
        assertThat(model.plugins()).isEmpty();
    }

    @Test
    void parseAtRootReadsPomFromDisk(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>3.3.0</version>
              </parent>
            </project>
            """);

        var maybeModel = parser.parseAtRoot(tmp);

        assertThat(maybeModel).isPresent();
        assertThat(maybeModel.get().parentArtifactId()).isEqualTo("spring-boot-starter-parent");
    }

    @Test
    void parseAtRootMissingPomReturnsEmpty(@TempDir Path tmp) {
        assertThat(parser.parseAtRoot(tmp)).isEmpty();
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
