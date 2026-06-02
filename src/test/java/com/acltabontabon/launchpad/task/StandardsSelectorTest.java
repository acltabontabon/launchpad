package com.acltabontabon.launchpad.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link StandardsSelector}: scope matching, opt-out filtering, stack normalisation. */
class StandardsSelectorTest {

    @Nested
    class ScopeApplies {

        @Test
        void appliesWhenScopeIsEmpty() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(StandardsSelector.scopeApplies(Scope.empty(), spring, Set.of("feature")))
                .isTrue();
        }

        @Test
        void appliesWhenFrameworkMatches() {
            var scope = new Scope(List.of(), List.of("spring-boot"), List.of(), List.of(), List.of());
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(StandardsSelector.scopeApplies(scope, spring, Set.of("feature"))).isTrue();
        }

        @Test
        void rejectsWhenFrameworkDoesNotMatch() {
            var scope = new Scope(List.of(), List.of("next"), List.of(), List.of(), List.of());
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(StandardsSelector.scopeApplies(scope, spring, Set.of("feature"))).isFalse();
        }

        @Test
        void appliesWhenTagsOverlap() {
            var scope = new Scope(List.of(), List.of(), List.of(), List.of(), List.of("rest", "errors"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(StandardsSelector.scopeApplies(scope, spring, Set.of("rest", "feature")))
                .isTrue();
        }

        @Test
        void rejectsWhenTagsDoNotOverlap() {
            var scope = new Scope(List.of(), List.of(), List.of(), List.of(), List.of("testing"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(StandardsSelector.scopeApplies(scope, spring, Set.of("rest", "feature")))
                .isFalse();
        }
    }

    @Nested
    class OverlapsTags {

        @Test
        void trueWhenRuleTagOptedOut() {
            var scope = new Scope(List.of(), List.of(), List.of(), List.of(),
                List.of("security", "crypto"));
            assertThat(StandardsSelector.overlapsTags(scope, Set.of("security"))).isTrue();
        }

        @Test
        void falseWhenNoOverlap() {
            var scope = new Scope(List.of(), List.of(), List.of(), List.of(),
                List.of("rest", "errors"));
            assertThat(StandardsSelector.overlapsTags(scope, Set.of("security"))).isFalse();
        }

        @Test
        void falseForEmptyScope() {
            assertThat(StandardsSelector.overlapsTags(Scope.empty(), Set.of("security"))).isFalse();
        }
    }

    @Nested
    class StackNormalisation {

        @Test
        void frameworkLowercasesAndDashes() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(StandardsSelector.normaliseFramework(spring)).isEqualTo("spring-boot");
        }

        @Test
        void frameworkHandlesDots() {
            var spring = new StackProfile("Java", "Maven", "Spring.Boot", List.of());
            assertThat(StandardsSelector.normaliseFramework(spring)).isEqualTo("springboot");
        }

        @Test
        void languageLowercases() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(StandardsSelector.normaliseLanguage(spring)).isEqualTo("java");
        }

        @Test
        void nullStackOrFieldsHandled() {
            assertThat(StandardsSelector.normaliseFramework(null)).isNull();
            assertThat(StandardsSelector.normaliseLanguage(null)).isNull();
        }
    }

    @Nested
    class SelectRelevantStandards {

        @Test
        void filtersByFrameworkScope() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            var matching = new Rule("a", "A", "must", "x", null,
                new Scope(List.of(), List.of("spring-boot"), List.of(), List.of(), List.of()), null, null);
            var nonMatching = new Rule("b", "B", "must", "x", null,
                new Scope(List.of(), List.of("next"), List.of(), List.of(), List.of()), null, null);

            var result = StandardsSelector.selectRelevantStandards(
                spring, "create endpoint", List.of(),
                List.of(matching, nonMatching), List.of(), List.of());
            assertThat(result.rules()).extracting(Rule::id).containsExactly("a");
        }

        @Test
        void dropsRulesUserOptedOutOf() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            var auth = mustRule("auth", "Authentication Required by Default");
            var rate = mustRule("rate", "Documented Rate-Limit Policy");
            var history = List.of(
                new TaskTurn("What authentication is required by default?", "no need"));

            var result = StandardsSelector.selectRelevantStandards(
                spring, "create endpoint", history,
                List.of(auth, rate), List.of(), List.of());
            assertThat(result.rules()).extracting(Rule::id).contains("rate").doesNotContain("auth");
        }

        @Test
        void nullInputListsHandledGracefully() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            var result = StandardsSelector.selectRelevantStandards(
                spring, "task", List.of(), null, null, null);
            assertThat(result.rules()).isEmpty();
            assertThat(result.skills()).isEmpty();
            assertThat(result.checklists()).isEmpty();
        }
    }

    private static Rule mustRule(String id, String title) {
        return new Rule(id, title, "must", "A description.", "A rationale.", Scope.empty(), null, null);
    }
}
