package com.acltabontabon.launchpad.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.SpringProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class FacetPromptComposerTest {

    private final FacetPromptComposer composer = new FacetPromptComposer();

    // --- Generic composer behavior (driven through the spring tree) ---

    @Test
    void baseAloneIsReturnedWhenFacetsAreEmpty() {
        String result = composer.compose(PromptSelector.Kind.SUMMARY, "spring", List.of());
        assertThat(result).contains("PROJECT CONTEXT:");
        assertThat(result).contains("## Overview");
        assertThat(result).doesNotContain("Spring MVC is on the classpath");
    }

    @Test
    void baseAloneIsReturnedWhenFacetsAreNull() {
        String result = composer.compose(PromptSelector.Kind.SUMMARY, "spring", null);
        assertThat(result).contains("PROJECT CONTEXT:");
        assertThat(result).doesNotContain("Spring MVC is on the classpath");
    }

    @Test
    void facetIsInsertedBeforeProjectContextMarker() {
        String result = composer.compose(PromptSelector.Kind.SUMMARY, "spring", List.of("web-mvc"));
        int facetIdx = result.indexOf("Spring MVC is on the classpath");
        int contextIdx = result.indexOf("PROJECT CONTEXT:");
        assertThat(facetIdx).isPositive();
        assertThat(facetIdx).isLessThan(contextIdx);
    }

    @Test
    void facetsAppearInTheOrderTheyWerePassed() {
        String result = composer.compose(PromptSelector.Kind.SUMMARY, "spring",
            List.of("web-mvc", "persistence-jpa", "spring-ai"));
        int mvcIdx = result.indexOf("Spring MVC is on the classpath");
        int jpaIdx = result.indexOf("Spring Data JPA is on the classpath");
        int aiIdx = result.indexOf("Spring AI is on the classpath");
        assertThat(mvcIdx).isPositive();
        assertThat(jpaIdx).isGreaterThan(mvcIdx);
        assertThat(aiIdx).isGreaterThan(jpaIdx);
    }

    @Test
    void readFacetSectionReturnsNullForMissingFacet() {
        String result = composer.readFacetSection("spring", "does-not-exist", PromptSelector.Kind.SUMMARY);
        assertThat(result).isNull();
    }

    // --- Spring-tree contract preserved ---

    @Test
    void webMvcSummaryAppearsViaSpringProfile() {
        var profile = springProfile(true, false, false);
        String result = composer.compose(PromptSelector.Kind.SUMMARY, "spring", profile.facets());
        assertThat(result).contains("Spring MVC is on the classpath");
    }

    @Test
    void skillsKindPullsTheSkillsSectionNotSummary() {
        var facets = List.of("persistence-jpa");
        String summary = composer.compose(PromptSelector.Kind.SUMMARY, "spring", facets);
        String skills = composer.compose(PromptSelector.Kind.SKILLS, "spring", facets);
        assertThat(summary).contains("Spring Data JPA is on the classpath");
        assertThat(summary).doesNotContain("add-jpa-entity-and-repository");
        assertThat(skills).contains("add-jpa-entity-and-repository");
        assertThat(skills).doesNotContain("Spring Data JPA is on the classpath");
    }

    @Test
    void rulesKindPullsTheRulesSection() {
        String rules = composer.compose(PromptSelector.Kind.RULES, "spring", List.of("web-mvc"));
        assertThat(rules).contains("Thin controllers");
        assertThat(rules).doesNotContain("Spring MVC is on the classpath");
    }

    @Test
    void starterLibraryFacetReframesTheSummary() {
        String summary = composer.compose(PromptSelector.Kind.SUMMARY, "spring",
            List.of("starter-library"));
        assertThat(summary).contains("auto-configuration library");
        assertThat(summary).contains("NOT a runnable application");
    }

    @Test
    void starterLibrarySectionAppearsBeforeOtherFacetSections() {
        String summary = composer.compose(PromptSelector.Kind.SUMMARY, "spring",
            List.of("starter-library", "persistence-jpa"));
        int libIdx = summary.indexOf("auto-configuration library");
        int jpaIdx = summary.indexOf("Spring Data JPA is on the classpath");
        assertThat(libIdx).isPositive();
        assertThat(jpaIdx).isGreaterThan(libIdx);
    }

    private static SpringProfile springProfile(boolean web, boolean jpa, boolean springAi) {
        return new SpringProfile(web, false, jpa, false, false, springAi, false, false, false, false, false, false);
    }
}
