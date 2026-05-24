package com.acltabontabon.launchpad.springboot.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActuatorDetectorTest {

    private static final Dependency ACTUATOR = new Dependency(
        "org.springframework.boot:spring-boot-starter-actuator", "3.0.0", null);
    private static final Dependency WEB = new Dependency(
        "org.springframework.boot:spring-boot-starter-web", "3.0.0", null);

    @Test
    void emptyWhenNoActuator() {
        var result = ActuatorDetector.detect(List.of(WEB), Map.of(), null);
        assertThat(result.endpoints()).isEmpty();
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void healthIsAlwaysIncludedWhenActuatorPresent() {
        var result = ActuatorDetector.detect(List.of(ACTUATOR), Map.of(), null);
        assertThat(result.endpoints()).extracting(Endpoint::path).containsExactly("/actuator/health");
    }

    @Test
    void parsesPropertiesExposureInclude() {
        var props = """
            spring.application.name=demo
            management.endpoints.web.exposure.include=health,info,metrics
            """;
        var result = ActuatorDetector.detect(List.of(ACTUATOR),
            Map.of("application.properties", props), null);
        assertThat(result.endpoints()).extracting(Endpoint::path)
            .containsExactlyInAnyOrder("/actuator/health", "/actuator/info", "/actuator/metrics");
    }

    @Test
    void starExpandsToWellKnownSet() {
        var props = "management.endpoints.web.exposure.include=*\n";
        var result = ActuatorDetector.detect(List.of(ACTUATOR),
            Map.of("application.properties", props), null);
        assertThat(result.endpoints()).extracting(Endpoint::path)
            .contains("/actuator/health", "/actuator/info", "/actuator/metrics", "/actuator/env");
        assertThat(result.endpoints().size()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void honorsCustomBasePathFromProperties() {
        var props = """
            management.endpoints.web.base-path=/manage
            management.endpoints.web.exposure.include=health,info
            """;
        var result = ActuatorDetector.detect(List.of(ACTUATOR),
            Map.of("application.properties", props), null);
        assertThat(result.endpoints()).extracting(Endpoint::path)
            .containsExactlyInAnyOrder("/manage/health", "/manage/info");
    }

    @Test
    void parsesYamlExposureInclude() {
        var yml = """
            spring:
              application:
                name: demo
            management:
              endpoints:
                web:
                  exposure:
                    include: health,info,prometheus
            """;
        var result = ActuatorDetector.detect(List.of(ACTUATOR),
            Map.of("application.yml", yml), null);
        assertThat(result.endpoints()).extracting(Endpoint::path)
            .containsExactlyInAnyOrder("/actuator/health", "/actuator/info", "/actuator/prometheus");
    }

    @Test
    void infoNoteReferencesBuildInfoWhenGoalIsConfigured() {
        var props = "management.endpoints.web.exposure.include=health,info\n";
        var pom = """
            <plugin>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-maven-plugin</artifactId>
              <executions>
                <execution>
                  <goals>
                    <goal>build-info</goal>
                  </goals>
                </execution>
              </executions>
            </plugin>
            """;
        var result = ActuatorDetector.detect(List.of(ACTUATOR),
            Map.of("application.properties", props), pom);
        assertThat(result.notes().get("GET /actuator/info"))
            .contains("build-info.properties");
    }

    @Test
    void infoNoteFallsBackToGenericWhenBuildInfoAbsent() {
        var props = "management.endpoints.web.exposure.include=info\n";
        var result = ActuatorDetector.detect(List.of(ACTUATOR),
            Map.of("application.properties", props), "<project></project>");
        assertThat(result.notes().get("GET /actuator/info")).isEqualTo("Application metadata");
    }

    @Test
    void healthNoteIsEmpty() {
        var result = ActuatorDetector.detect(List.of(ACTUATOR), Map.of(), null);
        assertThat(result.notes().get("GET /actuator/health")).isEmpty();
    }
}
