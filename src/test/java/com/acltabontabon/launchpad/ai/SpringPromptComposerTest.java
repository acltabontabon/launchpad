package com.acltabontabon.launchpad.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.SpringProfile;
import org.junit.jupiter.api.Test;

class SpringPromptComposerTest {

    private final SpringPromptComposer composer = new SpringPromptComposer();

    @Test
    void baseAloneIsReturnedWhenProfileIsNull() {
        String result = composer.compose(PromptSelector.Kind.SUMMARY, null);
        assertThat(result).contains("PROJECT CONTEXT:");
        assertThat(result).contains("## Overview");
        assertThat(result).doesNotContain("Spring MVC is on the classpath");
    }

    @Test
    void baseAloneIsReturnedWhenProfileHasNoFacets() {
        String result = composer.compose(PromptSelector.Kind.SUMMARY, SpringProfile.empty());
        assertThat(result).contains("PROJECT CONTEXT:");
        assertThat(result).doesNotContain("Spring MVC is on the classpath");
        assertThat(result).doesNotContain("Spring WebFlux is on the classpath");
    }

    @Test
    void webMvcFacetSummarySectionAppearsInComposedSummary() {
        var profile = profileWithFacets(true, false, false);
        String result = composer.compose(PromptSelector.Kind.SUMMARY, profile);
        assertThat(result).contains("Spring MVC is on the classpath");
    }

    @Test
    void facetIsInsertedBeforeProjectContextMarker() {
        var profile = profileWithFacets(true, false, false);
        String result = composer.compose(PromptSelector.Kind.SUMMARY, profile);
        int facetIdx = result.indexOf("Spring MVC is on the classpath");
        int contextIdx = result.indexOf("PROJECT CONTEXT:");
        assertThat(facetIdx).isPositive();
        assertThat(facetIdx).isLessThan(contextIdx);
    }

    @Test
    void multipleFacetsAppearInProfileOrder() {
        var profile = profileWithFacets(true, true, true);
        String result = composer.compose(PromptSelector.Kind.SUMMARY, profile);
        int mvcIdx = result.indexOf("Spring MVC is on the classpath");
        int jpaIdx = result.indexOf("Spring Data JPA is on the classpath");
        int aiIdx = result.indexOf("Spring AI is on the classpath");
        assertThat(mvcIdx).isPositive();
        assertThat(jpaIdx).isGreaterThan(mvcIdx);
        assertThat(aiIdx).isGreaterThan(jpaIdx);
    }

    @Test
    void skillsKindPullsTheSkillsSectionNotSummary() {
        var profile = profileWithFacets(false, true, false);
        String summary = composer.compose(PromptSelector.Kind.SUMMARY, profile);
        String skills = composer.compose(PromptSelector.Kind.SKILLS, profile);
        assertThat(summary).contains("Spring Data JPA is on the classpath");
        assertThat(summary).doesNotContain("add-jpa-entity-and-repository");
        assertThat(skills).contains("add-jpa-entity-and-repository");
        assertThat(skills).doesNotContain("Spring Data JPA is on the classpath");
    }

    @Test
    void rulesKindPullsTheRulesSection() {
        var profile = profileWithFacets(true, false, false);
        String rules = composer.compose(PromptSelector.Kind.RULES, profile);
        assertThat(rules).contains("Thin controllers");
        assertThat(rules).doesNotContain("Spring MVC is on the classpath");
    }

    @Test
    void readFacetSectionReturnsNullForMissingFacet() {
        String result = composer.readFacetSection("does-not-exist", PromptSelector.Kind.SUMMARY);
        assertThat(result).isNull();
    }

    @Test
    void starterLibraryFacetReframesTheSummary() {
        var profile = new SpringProfile(false, false, false, false, false, false, false, false,
            false, false, false, true);
        String summary = composer.compose(PromptSelector.Kind.SUMMARY, profile);
        assertThat(summary).contains("auto-configuration library");
        assertThat(summary).contains("NOT a runnable application");
    }

    @Test
    void starterLibrarySectionAppearsBeforeOtherFacetSections() {
        var profile = new SpringProfile(false, false, true, false, false, false, false, false,
            false, false, false, true);
        String summary = composer.compose(PromptSelector.Kind.SUMMARY, profile);
        int libIdx = summary.indexOf("auto-configuration library");
        int jpaIdx = summary.indexOf("Spring Data JPA is on the classpath");
        assertThat(libIdx).isPositive();
        assertThat(jpaIdx).isGreaterThan(libIdx);
    }

    private static SpringProfile profileWithFacets(boolean web, boolean jpa, boolean springAi) {
        return new SpringProfile(web, false, jpa, false, false, springAi, false, false, false, false, false, false);
    }
}
