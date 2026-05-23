package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpringProfileDetectorTest {

    private final SpringProfileDetector detector = new SpringProfileDetector();

    @Test
    void emptyDependenciesYieldsEmptyProfile() {
        var profile = detector.detect(List.of());
        assertThat(profile.facets()).isEmpty();
    }

    @Test
    void springBootStarterWebMapsToWebMvcFacet() {
        var profile = detector.detect(List.of(
            dep("org.springframework.boot:spring-boot-starter-web")));
        assertThat(profile.web()).isTrue();
        assertThat(profile.webflux()).isFalse();
        assertThat(profile.facets()).contains("web-mvc").doesNotContain("web-webflux");
    }

    @Test
    void webfluxStarterMapsToWebfluxFacetNotMvc() {
        var profile = detector.detect(List.of(
            dep("org.springframework.boot:spring-boot-starter-webflux")));
        assertThat(profile.webflux()).isTrue();
        assertThat(profile.web()).isFalse();
        assertThat(profile.facets()).contains("web-webflux").doesNotContain("web-mvc");
    }

    @Test
    void bothMvcAndWebfluxEmitBothFacets() {
        var profile = detector.detect(List.of(
            dep("org.springframework.boot:spring-boot-starter-web"),
            dep("org.springframework.boot:spring-boot-starter-webflux")));
        assertThat(profile.facets()).contains("web-mvc", "web-webflux");
    }

    @Test
    void jpaStarterMapsToJpaFacet() {
        var profile = detector.detect(List.of(
            dep("org.springframework.boot:spring-boot-starter-data-jpa")));
        assertThat(profile.jpa()).isTrue();
        assertThat(profile.facets()).contains("persistence-jpa");
    }

    @Test
    void springAiGroupMapsToSpringAiFacet() {
        var profile = detector.detect(List.of(
            dep("org.springframework.ai:spring-ai-openai-spring-boot-starter")));
        assertThat(profile.springAi()).isTrue();
        assertThat(profile.facets()).contains("spring-ai");
    }

    @Test
    void springSecurityStarterMapsToSecurityFacet() {
        var profile = detector.detect(List.of(
            dep("org.springframework.boot:spring-boot-starter-security")));
        assertThat(profile.springSecurity()).isTrue();
        assertThat(profile.facets()).contains("spring-security");
    }

    @Test
    void kafkaAndRabbitDetectedIndependently() {
        var profile = detector.detect(List.of(
            dep("org.springframework.kafka:spring-kafka"),
            dep("org.springframework.boot:spring-boot-starter-amqp")));
        assertThat(profile.kafka()).isTrue();
        assertThat(profile.rabbit()).isTrue();
        assertThat(profile.facets()).contains("messaging-kafka", "messaging-rabbit");
    }

    @Test
    void graalvmBuildPluginMapsToNativeFacet() {
        var profile = detector.detect(List.of(
            dep("org.graalvm.buildtools:native-maven-plugin")));
        assertThat(profile.nativeImage()).isTrue();
        assertThat(profile.facets()).contains("graalvm-native");
    }

    @Test
    void typicalSpringBootProjectComposesExpectedFacets() {
        var profile = detector.detect(List.of(
            dep("org.springframework.boot:spring-boot-starter-web"),
            dep("org.springframework.boot:spring-boot-starter-data-jpa"),
            dep("org.springframework.boot:spring-boot-starter-security"),
            dep("org.postgresql:postgresql")));
        assertThat(profile.facets())
            .containsExactly("web-mvc", "persistence-jpa", "spring-security");
    }

    @Test
    void unrelatedDependenciesProduceNoFacets() {
        var profile = detector.detect(List.of(
            dep("org.postgresql:postgresql"),
            dep("com.fasterxml.jackson.core:jackson-databind")));
        assertThat(profile.facets()).isEmpty();
    }

    @Test
    void autoConfigurationImportsMarkerSetsStarterLibraryFacet() {
        var signals = new SpringProfileDetector.Signals(true, false, false);
        var profile = detector.detect(List.of(), signals);
        assertThat(profile.starterLibrary()).isTrue();
        assertThat(profile.facets()).contains("starter-library");
    }

    @Test
    void legacySpringFactoriesMarkerAlsoSetsStarterLibraryFacet() {
        var signals = new SpringProfileDetector.Signals(false, true, false);
        var profile = detector.detect(List.of(), signals);
        assertThat(profile.starterLibrary()).isTrue();
    }

    @Test
    void autoConfigurationAnnotationAloneIsEnoughToFlagStarterLibrary() {
        var signals = new SpringProfileDetector.Signals(false, false, true);
        var profile = detector.detect(List.of(), signals);
        assertThat(profile.starterLibrary()).isTrue();
        assertThat(profile.facets()).contains("starter-library");
    }

    @Test
    void absentSignalsLeaveStarterLibraryFalse() {
        var profile = detector.detect(List.of(dep("org.springframework.boot:spring-boot-starter-web")));
        assertThat(profile.starterLibrary()).isFalse();
        assertThat(profile.facets()).doesNotContain("starter-library");
    }

    @Test
    void allSignalsFalseExplicitlyLeavesStarterLibraryFalse() {
        var profile = detector.detect(
            List.of(dep("org.springframework.boot:spring-boot-starter-web")),
            SpringProfileDetector.Signals.NONE);
        assertThat(profile.starterLibrary()).isFalse();
    }

    @Test
    void starterLibraryFacetAppearsFirstWhenCombinedWithSubStackFacets() {
        var signals = new SpringProfileDetector.Signals(true, false, false);
        var profile = detector.detect(List.of(
            dep("org.springframework.boot:spring-boot-starter-data-jpa"),
            dep("org.springframework.ai:spring-ai-openai-spring-boot-starter")), signals);
        assertThat(profile.facets())
            .startsWith("starter-library")
            .contains("persistence-jpa", "spring-ai");
    }

    private static Dependency dep(String name) {
        return new Dependency(name, "1.0", "runtime");
    }
}
