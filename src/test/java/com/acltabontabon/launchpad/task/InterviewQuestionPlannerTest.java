package com.acltabontabon.launchpad.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link InterviewQuestionPlanner}: question extraction,
 * near-duplicate detection, must-rule forcing, critic-response interpretation,
 * and stack-aware discovery hints. No LLM round-trips here.
 */
class InterviewQuestionPlannerTest {

    @Nested
    class ExtractQuestion {

        @Test
        void picksLastQuestionEndingInQuestionMark() {
            var picked = InterviewQuestionPlanner.extractQuestion(
                "Some preamble.\nMore text.\nIs this the question?\nTrailing line.");
            assertThat(picked).isEqualTo("Is this the question?");
        }

        @Test
        void preferQuestionMarkLineOverLastNonEmpty() {
            var picked = InterviewQuestionPlanner.extractQuestion(
                "Sure! Let me ask:\nWhat is your name?\nThanks");
            assertThat(picked).isEqualTo("What is your name?");
        }

        @Test
        void fallsBackToLastNonEmptyWhenNoQuestionMark() {
            var picked = InterviewQuestionPlanner.extractQuestion(
                "Statement one.\nStatement two without any question mark.");
            assertThat(picked).isEqualTo("Statement two without any question mark.");
        }

        @Test
        void returnsDoneForNullOrEmpty() {
            assertThat(InterviewQuestionPlanner.extractQuestion(null)).isEqualTo("__DONE__");
            assertThat(InterviewQuestionPlanner.extractQuestion("")).isEqualTo("__DONE__");
            assertThat(InterviewQuestionPlanner.extractQuestion("   \n\n")).isEqualTo("__DONE__");
        }

        @Test
        void detectsDoneTokenAnywhereInOutput() {
            assertThat(InterviewQuestionPlanner.extractQuestion("__DONE__")).isEqualTo("__DONE__");
            assertThat(InterviewQuestionPlanner.extractQuestion("Some text __DONE__ trailing"))
                .isEqualTo("__DONE__");
        }

        @Test
        void stripsQuestionNumberPrefix() {
            assertThat(InterviewQuestionPlanner.extractQuestion("Q1: Should we add auth?"))
                .isEqualTo("Should we add auth?");
            assertThat(InterviewQuestionPlanner.extractQuestion("Q3. Should we add auth?"))
                .isEqualTo("Should we add auth?");
            assertThat(InterviewQuestionPlanner.extractQuestion("3. Should we add auth?"))
                .isEqualTo("Should we add auth?");
        }

        @Test
        void stripsBoldMarkersAroundQuestionNumber() {
            assertThat(InterviewQuestionPlanner.extractQuestion("**Q2:** Should we add auth?"))
                .isEqualTo("Should we add auth?");
        }
    }

    @Nested
    class IsNearDuplicateOfPrior {

        @Test
        void falseForEmptyHistory() {
            assertThat(InterviewQuestionPlanner.isNearDuplicateOfPrior(
                "Should we add auth to the new endpoint?", List.of())).isFalse();
        }

        @Test
        void falseForVeryShortQuestion() {
            assertThat(InterviewQuestionPlanner.isNearDuplicateOfPrior(
                "Why?", List.of(new TaskTurn("Why?", "because")))).isFalse();
        }

        @Test
        void trueWhenContainmentExceedsThreshold() {
            var history = List.of(
                new TaskTurn("What type of authentication is required for this new API?", "no auth"));
            var newQ = "What type of authentication is required for this new API, "
                + "specifically which method should be used for user-provided input?";
            assertThat(InterviewQuestionPlanner.isNearDuplicateOfPrior(newQ, history)).isTrue();
        }

        @Test
        void trueWhenJaccardOverlapExceedsThreshold() {
            var history = List.of(
                new TaskTurn("Which rate-limit policy should the endpoint use?", "none"));
            var newQ = "Should the endpoint use a specific rate-limit policy?";
            assertThat(InterviewQuestionPlanner.isNearDuplicateOfPrior(newQ, history)).isTrue();
        }

        @Test
        void stemTolerantSoPluralsMatch() {
            var history = List.of(
                new TaskTurn("What error format do these endpoints use?", "json"));
            var newQ = "Which error format does this endpoint use?";
            assertThat(InterviewQuestionPlanner.isNearDuplicateOfPrior(newQ, history)).isTrue();
        }

        @Test
        void falseForDifferentTopic() {
            var history = List.of(
                new TaskTurn("What rate-limit policy applies?", "none"));
            assertThat(InterviewQuestionPlanner.isNearDuplicateOfPrior(
                "What error format should the API return?", history)).isFalse();
        }
    }

    @Nested
    class IsNearDuplicateOfPriorFalsePositives {

        @Test
        void differentAuthAnglesNotDuplicate() {
            var history = List.of(
                new TaskTurn("Should the endpoint require authentication?", "yes"));
            var newQ = "How long should auth tokens stay valid before refresh?";
            assertThat(InterviewQuestionPlanner.isNearDuplicateOfPrior(newQ, history)).isFalse();
        }

        @Test
        void unrelatedShortQuestionNotDuplicate() {
            var history = List.of(
                new TaskTurn("Which authentication scheme applies?", "team default"));
            assertThat(InterviewQuestionPlanner.isNearDuplicateOfPrior(
                "What error envelope should responses use?", history)).isFalse();
        }
    }

    @Nested
    class PickNextUncoveredMustRule {

        @Test
        void returnsNullWhenAllRulesCovered() {
            var rules = List.of(mustRule("auth", "Authentication Required by Default"));
            var history = List.of(
                new TaskTurn("Should authentication be required by default?", "yes"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(InterviewQuestionPlanner.pickNextUncoveredMustRule(
                history, rules, spring, Set.of("rest", "feature"), Set.of())).isNull();
        }

        @Test
        void returnsUncoveredMustRule() {
            var rules = List.of(
                mustRule("auth", "Authentication Required by Default"),
                mustRule("rate", "Documented Rate-Limit Policy"));
            var history = List.of(
                new TaskTurn("Should authentication be required by default?", "yes"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            var picked = InterviewQuestionPlanner.pickNextUncoveredMustRule(
                history, rules, spring, Set.of("rest", "feature"), Set.of());
            assertThat(picked).isNotNull();
            assertThat(picked.id()).isEqualTo("rate");
        }

        @Test
        void skipsOptedOutRules() {
            var rules = List.of(mustRule("auth", "Authentication Required by Default"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(InterviewQuestionPlanner.pickNextUncoveredMustRule(
                List.of(), rules, spring, Set.of("rest", "feature"), Set.of("auth"))).isNull();
        }

        @Test
        void skipsNonMustSeverity() {
            var rules = List.of(rule("optional", "should", "Some Should-Severity Rule"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(InterviewQuestionPlanner.pickNextUncoveredMustRule(
                List.of(), rules, spring, Set.of("rest", "feature"), Set.of())).isNull();
        }
    }

    @Nested
    class InterpretCritiqueResponse {

        @Test
        void readyTokenMeansReady() {
            assertThat(InterviewQuestionPlanner.interpretCritiqueResponse("__OK__", List.of()))
                .isEqualTo("__OK__");
        }

        @Test
        void readyTokenAmidPreambleStillMeansReady() {
            assertThat(InterviewQuestionPlanner.interpretCritiqueResponse(
                "Looks good to me. __OK__", List.of())).isEqualTo("__OK__");
        }

        @Test
        void emptyOrNullResponseMeansReady() {
            assertThat(InterviewQuestionPlanner.interpretCritiqueResponse(null, List.of()))
                .isEqualTo("__OK__");
            assertThat(InterviewQuestionPlanner.interpretCritiqueResponse("", List.of()))
                .isEqualTo("__OK__");
            assertThat(InterviewQuestionPlanner.interpretCritiqueResponse("  \n\n", List.of()))
                .isEqualTo("__OK__");
        }

        @Test
        void doneTokenAlsoTreatedAsReady() {
            assertThat(InterviewQuestionPlanner.interpretCritiqueResponse("__DONE__", List.of()))
                .isEqualTo("__OK__");
        }

        @Test
        void followUpQuestionPassesThrough() {
            var picked = InterviewQuestionPlanner.interpretCritiqueResponse(
                "What should the endpoint return for unknown product IDs?", List.of());
            assertThat(picked).isEqualTo("What should the endpoint return for unknown product IDs?");
        }

        @Test
        void questionWithPreambleIsStillExtracted() {
            var picked = InterviewQuestionPlanner.interpretCritiqueResponse(
                "After reviewing the transcript, one gap remains.\n"
                    + "What should the endpoint return for unknown product IDs?",
                List.of());
            assertThat(picked).isEqualTo("What should the endpoint return for unknown product IDs?");
        }

        @Test
        void nearDuplicateOfPriorQuestionCollapsesToReady() {
            var history = List.of(
                new TaskTurn("What type of authentication is required for this new API?", "no auth"));
            var picked = InterviewQuestionPlanner.interpretCritiqueResponse(
                "What type of authentication is required for this new API, specifically which method?",
                history);
            assertThat(picked).isEqualTo("__OK__");
        }

        @Test
        void numberedPrefixIsStripped() {
            var picked = InterviewQuestionPlanner.interpretCritiqueResponse(
                "Q1: What should the endpoint return for unknown product IDs?",
                List.of());
            assertThat(picked).isEqualTo("What should the endpoint return for unknown product IDs?");
        }
    }

    @Nested
    class DiscoveryHintFor {

        @Test
        void restTaskGetsResourceShape() {
            var hint = InterviewQuestionPlanner.discoveryHintFor("add a new endpoint", List.of());
            assertThat(hint).contains("resource").contains("HTTP method");
        }

        @Test
        void debuggingTaskGetsSymptomShape() {
            var hint = InterviewQuestionPlanner.discoveryHintFor("fix bug in login flow", List.of());
            assertThat(hint).contains("symptom").contains("reproduction");
        }

        @Test
        void refactoringTaskGetsMotivationShape() {
            var hint = InterviewQuestionPlanner.discoveryHintFor("refactor the user service", List.of());
            assertThat(hint).contains("motivation").contains("behaviour-preservation");
        }

        @Test
        void uiTaskGetsScreenShape() {
            var hint = InterviewQuestionPlanner.discoveryHintFor("add a new screen for settings", List.of());
            assertThat(hint).contains("screen").contains("user action");
        }

        @Test
        void aiTaskGetsModelShape() {
            var hint = InterviewQuestionPlanner.discoveryHintFor("add llm prompt template", List.of());
            assertThat(hint).contains("model role").contains("prompt inputs");
        }

        @Test
        void unclassifiedTaskGetsNeutralFallback() {
            var hint = InterviewQuestionPlanner.discoveryHintFor("xyz", List.of());
            assertThat(hint).contains("purpose").contains("inputs").contains("outputs");
        }
    }

    private static Rule rule(String id, String severity, String title) {
        return new Rule(id, title, severity, "A description.", "A rationale.", Scope.empty(), null, null);
    }

    private static Rule mustRule(String id, String title) {
        return rule(id, "must", title);
    }
}
