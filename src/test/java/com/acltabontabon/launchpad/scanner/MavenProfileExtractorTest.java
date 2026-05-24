package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MavenProfileExtractorTest {

    @Test
    void extractsProfileIdsAndActivation() {
        var pom = """
            <profiles>
              <profile>
                <id>pgo-instrument</id>
                <activation><activeByDefault>false</activeByDefault></activation>
                <build><plugins><plugin>
                  <configuration><buildArgs>
                    <buildArg>--pgo-instrument</buildArg>
                  </buildArgs></configuration>
                </plugin></plugins></build>
              </profile>
              <profile>
                <id>pgo-optimized</id>
                <build><plugins><plugin>
                  <configuration><buildArgs>
                    <buildArg>--pgo=profiles/default.iprof</buildArg>
                  </buildArgs></configuration>
                </plugin></plugins></build>
              </profile>
            </profiles>
            """;
        var profiles = MavenProfileExtractor.extract(pom);
        assertThat(profiles).hasSize(2);
        assertThat(profiles).extracting(MavenProfile::id)
            .containsExactly("pgo-instrument", "pgo-optimized");
        assertThat(profiles.get(0).keyFlags()).contains("--pgo-instrument");
        assertThat(profiles.get(1).keyFlags()).contains("--pgo=profiles/default.iprof");
    }

    @Test
    void capturesActiveByDefaultAndJdkActivation() {
        var pom = """
            <profile>
              <id>native</id>
              <activation>
                <activeByDefault>true</activeByDefault>
                <jdk>21</jdk>
              </activation>
            </profile>
            """;
        var profiles = MavenProfileExtractor.extract(pom);
        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).activation()).contains("active by default");
        assertThat(profiles.get(0).activation()).contains("JDK 21");
    }

    @Test
    void capturesArgLineTokens() {
        var pom = """
            <profile>
              <id>jvm-tweaks</id>
              <properties>
                <argLine>-XX:+UseG1GC -Xmx2g</argLine>
              </properties>
            </profile>
            """;
        var profiles = MavenProfileExtractor.extract(pom);
        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).keyFlags()).containsExactlyInAnyOrder("-XX:+UseG1GC", "-Xmx2g");
    }

    @Test
    void capturesSkipFlags() {
        var pom = """
            <profile>
              <id>quick</id>
              <properties>
                <skipTests>true</skipTests>
                <skipITs>true</skipITs>
              </properties>
            </profile>
            """;
        var profiles = MavenProfileExtractor.extract(pom);
        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).keyFlags()).containsExactlyInAnyOrder("skipTests", "skipITs");
    }

    @Test
    void returnsEmptyForNoProfiles() {
        var pom = """
            <project><modelVersion>4.0.0</modelVersion></project>
            """;
        assertThat(MavenProfileExtractor.extract(pom)).isEmpty();
    }

    @Test
    void toleratesMalformedXml() {
        var pom = "<profile><id>broken-profile</id"; // missing closing tags
        assertThat(MavenProfileExtractor.extract(pom)).isEmpty();
    }

    @Test
    void extractWithRawAlsoReturnsRawProfileBodies() {
        var pom = """
            <profile>
              <id>pgo-instrument</id>
              <build><plugins><plugin>
                <artifactId>native-maven-plugin</artifactId>
                <configuration><buildArgs><buildArg>--pgo-instrument</buildArg></buildArgs></configuration>
              </plugin></plugins></build>
            </profile>
            """;
        var result = MavenProfileExtractor.extractWithRaw(pom);
        assertThat(result.profiles()).extracting(MavenProfile::id).containsExactly("pgo-instrument");
        assertThat(result.rawBodies()).containsKey("pgo-instrument");
        assertThat(result.rawBodies().get("pgo-instrument"))
            .contains("native-maven-plugin")
            .contains("--pgo-instrument");
    }

    @Test
    void skipsProfilesWithBlankId() {
        var pom = """
            <profile>
              <id></id>
              <build/>
            </profile>
            <profile>
              <id>valid</id>
              <build/>
            </profile>
            """;
        var profiles = MavenProfileExtractor.extract(pom);
        assertThat(profiles).extracting(MavenProfile::id).containsExactly("valid");
    }
}
