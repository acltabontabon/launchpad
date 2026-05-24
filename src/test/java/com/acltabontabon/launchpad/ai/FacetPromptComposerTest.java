package com.acltabontabon.launchpad.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.springboot.runtime.SpringProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class FacetPromptComposerTest {

    private final FacetPromptComposer composer = new FacetPromptComposer();

    // --- Generic composer behavior (driven through the spring tree) ---

    @Test
    void baseAloneIsReturnedWhenFacetsAreEmpty() {
        String result = composer.compose(PromptSelector.Kind.SKILLS, "spring", List.of());
        assertThat(result).contains("PROJECT CONTEXT:");
        assertThat(result).doesNotContain("add-rest-endpoint");
    }

    @Test
    void baseAloneIsReturnedWhenFacetsAreNull() {
        String result = composer.compose(PromptSelector.Kind.SKILLS, "spring", null);
        assertThat(result).contains("PROJECT CONTEXT:");
        assertThat(result).doesNotContain("add-rest-endpoint");
    }

    @Test
    void facetIsInsertedBeforeProjectContextMarker() {
        String result = composer.compose(PromptSelector.Kind.SKILLS, "spring", List.of("web-mvc"));
        int facetIdx = result.indexOf("add-rest-endpoint");
        int contextIdx = result.indexOf("PROJECT CONTEXT:");
        assertThat(facetIdx).isPositive();
        assertThat(facetIdx).isLessThan(contextIdx);
    }

    @Test
    void facetsAppearInTheOrderTheyWerePassed() {
        String result = composer.compose(PromptSelector.Kind.SKILLS, "spring",
            List.of("web-mvc", "persistence-jpa", "spring-ai"));
        int mvcIdx = result.indexOf("add-rest-endpoint");
        int jpaIdx = result.indexOf("add-jpa-entity-and-repository");
        int aiIdx = result.indexOf("add-spring-ai-call");
        assertThat(mvcIdx).isPositive();
        assertThat(jpaIdx).isGreaterThan(mvcIdx);
        assertThat(aiIdx).isGreaterThan(jpaIdx);
    }

    @Test
    void readFacetSectionReturnsNullForMissingFacet() {
        String result = composer.readFacetSection("spring", "does-not-exist", PromptSelector.Kind.SKILLS);
        assertThat(result).isNull();
    }

    // --- Spring-tree contract preserved ---

    @Test
    void webMvcSkillsAppearViaSpringProfile() {
        var profile = springProfile(true, false, false);
        String result = composer.compose(PromptSelector.Kind.SKILLS, "spring", profile.facets());
        assertThat(result).contains("add-rest-endpoint");
    }

    @Test
    void rulesKindPullsTheRulesSection() {
        String rules = composer.compose(PromptSelector.Kind.RULES, "spring", List.of("web-mvc"));
        assertThat(rules).contains("Thin controllers");
        assertThat(rules).doesNotContain("add-rest-endpoint");
    }

    @Test
    void skillsAndRulesPullDifferentSectionsFromTheSameFacet() {
        var facets = List.of("persistence-jpa");
        String skills = composer.compose(PromptSelector.Kind.SKILLS, "spring", facets);
        String rules = composer.compose(PromptSelector.Kind.RULES, "spring", facets);
        assertThat(skills).contains("add-jpa-entity-and-repository");
        assertThat(rules).doesNotContain("add-jpa-entity-and-repository");
    }

    private static SpringProfile springProfile(boolean web, boolean jpa, boolean springAi) {
        return new SpringProfile(web, false, jpa, false, false, springAi, false, false, false, false, false, false);
    }
}
