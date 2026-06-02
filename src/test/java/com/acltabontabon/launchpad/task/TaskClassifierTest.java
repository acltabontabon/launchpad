package com.acltabontabon.launchpad.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link TaskClassifier}: tagging, per-rule opt-outs, per-tag opt-outs. */
class TaskClassifierTest {

    @Nested
    class ClassifyTaskTags {

        @Test
        void apiInTaskTriggersRestHttpDeliveryNotAi() {
            var tags = TaskClassifier.classifyTaskTags("i want to create new api", List.of());
            assertThat(tags).contains("rest", "http", "delivery", "feature");
            assertThat(tags).doesNotContain("ai");
        }

        @Test
        void llmKeywordTriggersAiTag() {
            var tags = TaskClassifier.classifyTaskTags("add llm prompt template", List.of());
            assertThat(tags).contains("ai");
        }

        @Test
        void everyTaskGetsFeatureTag() {
            assertThat(TaskClassifier.classifyTaskTags("anything goes here", List.of()))
                .contains("feature");
        }

        @Test
        void includesAnswerKeywordsTooNotJustTask() {
            var tags = TaskClassifier.classifyTaskTags("vague task",
                List.of(new TaskTurn("What kind?", "an HTTP endpoint")));
            assertThat(tags).contains("rest");
        }

        @Test
        void detectsRestCollectionForListKeywords() {
            var tags = TaskClassifier.classifyTaskTags(
                "add a search endpoint that returns all users", List.of());
            assertThat(tags).contains("rest", "rest-collection");
        }

        @Test
        void detectsRestMutationForCreateKeywords() {
            var tags = TaskClassifier.classifyTaskTags(
                "add an endpoint to create a new user", List.of());
            assertThat(tags).contains("rest", "rest-mutation");
        }

        @Test
        void simpleSingleResourceTaskHasNeitherSubTag() {
            var tags = TaskClassifier.classifyTaskTags(
                "i want to create new api for greetings", List.of());
            assertThat(tags).contains("rest");
            assertThat(tags).doesNotContain("rest-collection");
        }
    }

    @Nested
    class DetectOptedOutRules {

        @Test
        void emptyWhenNoNegations() {
            var rules = List.of(rule("auth-rule", "Authentication Required by Default"));
            var history = List.of(
                new TaskTurn("Should auth be required?", "yes please"));
            assertThat(TaskClassifier.detectOptedOutRules(rules, history)).isEmpty();
        }

        @Test
        void findsRulesWhoseTitleMatchesNegatedQuestion() {
            var rules = List.of(
                rule("auth-rule", "Authentication Required by Default"),
                rule("rate-rule", "Documented Rate-Limit Policy"));
            var history = List.of(
                new TaskTurn("What authentication is required by default?", "no need"));
            var optedOut = TaskClassifier.detectOptedOutRules(rules, history);
            assertThat(optedOut).contains("auth-rule");
            assertThat(optedOut).doesNotContain("rate-rule");
        }

        @Test
        void stemTolerantMatchesPluralsToSingulars() {
            var rules = List.of(
                rule("auth-defaults", "Authentication and Session Defaults Are Secure"));
            var history = List.of(
                new TaskTurn("What authentication is required by default?", "no need"));
            assertThat(TaskClassifier.detectOptedOutRules(rules, history))
                .contains("auth-defaults");
        }
    }

    @Nested
    class DetectOptedOutTags {

        @Test
        void detectsSecurityOptOutFromAuthQuestion() {
            var history = List.of(
                new TaskTurn("Is authentication required?", "no need"));
            var tags = TaskClassifier.detectOptedOutTags(history);
            assertThat(tags).contains("security", "auth", "crypto");
        }

        @Test
        void detectsObservabilityOptOutFromLoggingQuestion() {
            var history = List.of(
                new TaskTurn("Do we need detailed logging?", "skip"));
            var tags = TaskClassifier.detectOptedOutTags(history);
            assertThat(tags).contains("observability");
        }

        @Test
        void noOptOutWhenAnswerIsAffirmative() {
            var history = List.of(
                new TaskTurn("Is authentication required?", "yes"));
            assertThat(TaskClassifier.detectOptedOutTags(history)).isEmpty();
        }
    }

    private static Rule rule(String id, String title) {
        return new Rule(id, title, "must", "A description.", "A rationale.", Scope.empty(), null, null);
    }
}
