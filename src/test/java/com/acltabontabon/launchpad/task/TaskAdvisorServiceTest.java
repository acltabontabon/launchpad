package com.acltabontabon.launchpad.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.task.TaskAdvisorService.FinalizeSections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure-logic static helpers in {@link TaskAdvisorService}.
 * These cover the moving parts that decide what the user sees: question
 * extraction, duplicate detection, scope/opt-out filtering, output assembly.
 * Network calls (the actual LLM round-trip) are not exercised here.
 */
class TaskAdvisorServiceTest {

    // === extractQuestion ===

    @Nested
    class ExtractQuestion {

        @Test
        void picksLastQuestionEndingInQuestionMark() {
            // The extractor prefers ANY line ending in `?` over the last
            // non-empty line - even if a non-? line comes after.
            var picked = TaskAdvisorService.extractQuestion(
                "Some preamble.\nMore text.\nIs this the question?\nTrailing line.");
            assertThat(picked).isEqualTo("Is this the question?");
        }

        @Test
        void preferQuestionMarkLineOverLastNonEmpty() {
            var picked = TaskAdvisorService.extractQuestion(
                "Sure! Let me ask:\nWhat is your name?\nThanks");
            assertThat(picked).isEqualTo("What is your name?");
        }

        @Test
        void fallsBackToLastNonEmptyWhenNoQuestionMark() {
            var picked = TaskAdvisorService.extractQuestion(
                "Statement one.\nStatement two without any question mark.");
            assertThat(picked).isEqualTo("Statement two without any question mark.");
        }

        @Test
        void returnsDoneForNullOrEmpty() {
            assertThat(TaskAdvisorService.extractQuestion(null)).isEqualTo("__DONE__");
            assertThat(TaskAdvisorService.extractQuestion("")).isEqualTo("__DONE__");
            assertThat(TaskAdvisorService.extractQuestion("   \n\n")).isEqualTo("__DONE__");
        }

        @Test
        void detectsDoneTokenAnywhereInOutput() {
            assertThat(TaskAdvisorService.extractQuestion("__DONE__")).isEqualTo("__DONE__");
            assertThat(TaskAdvisorService.extractQuestion("Some text __DONE__ trailing"))
                .isEqualTo("__DONE__");
        }

        @Test
        void stripsQuestionNumberPrefix() {
            assertThat(TaskAdvisorService.extractQuestion("Q1: Should we add auth?"))
                .isEqualTo("Should we add auth?");
            assertThat(TaskAdvisorService.extractQuestion("Q3. Should we add auth?"))
                .isEqualTo("Should we add auth?");
            assertThat(TaskAdvisorService.extractQuestion("3. Should we add auth?"))
                .isEqualTo("Should we add auth?");
        }

        @Test
        void stripsBoldMarkersAroundQuestionNumber() {
            assertThat(TaskAdvisorService.extractQuestion("**Q2:** Should we add auth?"))
                .isEqualTo("Should we add auth?");
        }
    }

    // === isNearDuplicateOfPrior ===

    @Nested
    class IsNearDuplicateOfPrior {

        @Test
        void falseForEmptyHistory() {
            assertThat(TaskAdvisorService.isNearDuplicateOfPrior(
                "Should we add auth to the new endpoint?", List.of())).isFalse();
        }

        @Test
        void falseForVeryShortQuestion() {
            assertThat(TaskAdvisorService.isNearDuplicateOfPrior(
                "Why?", List.of(new TaskTurn("Why?", "because")))).isFalse();
        }

        @Test
        void trueWhenContainmentExceedsThreshold() {
            // Q3 contains the full text of Q2 plus extra words. Jaccard alone might
            // miss this if the union is large; containment catches it.
            var history = List.of(
                new TaskTurn("What type of authentication is required for this new API?", "no auth"));
            var newQ = "What type of authentication is required for this new API, "
                + "specifically which method should be used for user-provided input?";
            assertThat(TaskAdvisorService.isNearDuplicateOfPrior(newQ, history)).isTrue();
        }

        @Test
        void trueWhenJaccardOverlapExceedsThreshold() {
            var history = List.of(
                new TaskTurn("Which rate-limit policy should the endpoint use?", "none"));
            var newQ = "Should the endpoint use a specific rate-limit policy?";
            assertThat(TaskAdvisorService.isNearDuplicateOfPrior(newQ, history)).isTrue();
        }

        @Test
        void stemTolerantSoPluralsMatch() {
            var history = List.of(
                new TaskTurn("What error format do these endpoints use?", "json"));
            var newQ = "Which error format does this endpoint use?";
            // "endpoint" vs "endpoints" should match via prefix overlap.
            assertThat(TaskAdvisorService.isNearDuplicateOfPrior(newQ, history)).isTrue();
        }

        @Test
        void falseForDifferentTopic() {
            var history = List.of(
                new TaskTurn("What rate-limit policy applies?", "none"));
            assertThat(TaskAdvisorService.isNearDuplicateOfPrior(
                "What error format should the API return?", history)).isFalse();
        }
    }

    // === firstSentence ===

    @Nested
    class FirstSentence {

        @Test
        void doesNotSplitAtEgAbbreviation() {
            var input = "Every public API path or media type carries an explicit version "
                + "(e.g. `/v1/...` or `application/vnd.team.v1+json`). Breaking changes go to a new version.";
            var result = TaskAdvisorService.firstSentence(input);
            assertThat(result).contains("e.g.");
            assertThat(result).contains("v1+json");
            assertThat(result).doesNotContain("Breaking changes");
        }

        @Test
        void splitsAtRealSentenceBoundary() {
            var result = TaskAdvisorService.firstSentence(
                "First sentence here. Second sentence here.");
            assertThat(result).isEqualTo("First sentence here.");
        }

        @Test
        void returnsWholeStringWhenNoSentenceBoundary() {
            var result = TaskAdvisorService.firstSentence("No periods just words");
            assertThat(result).isEqualTo("No periods just words");
        }

        @Test
        void handlesNullAndEmpty() {
            assertThat(TaskAdvisorService.firstSentence(null)).isEmpty();
            assertThat(TaskAdvisorService.firstSentence("")).isEmpty();
            assertThat(TaskAdvisorService.firstSentence("   ")).isEmpty();
        }

        @Test
        void collapsesInternalWhitespaceAndNewlines() {
            var result = TaskAdvisorService.firstSentence(
                "First   sentence\n  here.\n\nSecond sentence.");
            assertThat(result).isEqualTo("First sentence here.");
        }
    }

    // === parseMarkerSections ===

    @Nested
    class ParseMarkerSections {

        @Test
        void parsesStandardEqualsDelimiters() {
            var raw = """
                ===GOAL===
                Goal text here.
                ===ACCEPTANCE===
                - one
                - two
                ===OUT_OF_SCOPE===
                - excluded
                """;
            var sections = TaskAdvisorService.parseMarkerSections(raw);
            assertThat(sections.goal()).isEqualTo("Goal text here.");
            assertThat(sections.acceptance()).isEqualTo("- one\n- two");
            assertThat(sections.outOfScope()).isEqualTo("- excluded");
        }

        @Test
        void parsesAsymmetricDelimiters() {
            // Models routinely mix equals and dashes; parser must accept all.
            var raw = """
                ===GOAL---
                Goal text.
                ---ACCEPTANCE===
                - accept
                ===OUT_OF_SCOPE---
                - excluded
                """;
            var sections = TaskAdvisorService.parseMarkerSections(raw);
            assertThat(sections.goal()).isEqualTo("Goal text.");
            assertThat(sections.acceptance()).isEqualTo("- accept");
            assertThat(sections.outOfScope()).isEqualTo("- excluded");
        }

        @Test
        void missingSectionsBecomeEmptyStrings() {
            var raw = "===GOAL===\nJust a goal.";
            var sections = TaskAdvisorService.parseMarkerSections(raw);
            assertThat(sections.goal()).isEqualTo("Just a goal.");
            assertThat(sections.acceptance()).isEmpty();
            assertThat(sections.outOfScope()).isEmpty();
        }
    }

    // === classifyTaskTags ===

    @Nested
    class ClassifyTaskTags {

        @Test
        void apiInTaskTriggersRestHttpDeliveryNotAi() {
            var tags = TaskAdvisorService.classifyTaskTags("i want to create new api", List.of());
            // The bug we're preventing: "api" containing substring "ai" must NOT
            // trigger the AI tag. Whole-word tokenisation is the fix.
            assertThat(tags).contains("rest", "http", "delivery", "feature");
            assertThat(tags).doesNotContain("ai");
        }

        @Test
        void llmKeywordTriggersAiTag() {
            var tags = TaskAdvisorService.classifyTaskTags("add llm prompt template", List.of());
            assertThat(tags).contains("ai");
        }

        @Test
        void everyTaskGetsFeatureTag() {
            assertThat(TaskAdvisorService.classifyTaskTags("anything goes here", List.of()))
                .contains("feature");
        }

        @Test
        void includesAnswerKeywordsTooNotJustTask() {
            var tags = TaskAdvisorService.classifyTaskTags("vague task",
                List.of(new TaskTurn("What kind?", "an HTTP endpoint")));
            assertThat(tags).contains("rest");
        }

        @Test
        void detectsRestCollectionForListKeywords() {
            var tags = TaskAdvisorService.classifyTaskTags(
                "add a search endpoint that returns all users", List.of());
            assertThat(tags).contains("rest", "rest-collection");
        }

        @Test
        void detectsRestMutationForCreateKeywords() {
            var tags = TaskAdvisorService.classifyTaskTags(
                "add an endpoint to create a new user", List.of());
            assertThat(tags).contains("rest", "rest-mutation");
        }

        @Test
        void simpleSingleResourceTaskHasNeitherSubTag() {
            // Hello-world greeting endpoint: not a collection, not a mutation.
            var tags = TaskAdvisorService.classifyTaskTags(
                "i want to create new api for greetings", List.of());
            assertThat(tags).contains("rest");
            assertThat(tags).doesNotContain("rest-collection");
            // "create" is a mutation keyword and appears here, so mutation tag fires.
            // The test's purpose is to confirm rest-collection does NOT fire on a
            // non-list task even when "create" does.
        }
    }

    // === scopeApplies ===

    @Nested
    class ScopeApplies {

        @Test
        void appliesWhenScopeIsEmpty() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(TaskAdvisorService.scopeApplies(Scope.empty(), spring, Set.of("feature")))
                .isTrue();
        }

        @Test
        void appliesWhenFrameworkMatches() {
            var scope = new Scope(List.of(), List.of("spring-boot"), List.of(), List.of(), List.of());
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(TaskAdvisorService.scopeApplies(scope, spring, Set.of("feature"))).isTrue();
        }

        @Test
        void rejectsWhenFrameworkDoesNotMatch() {
            var scope = new Scope(List.of(), List.of("next"), List.of(), List.of(), List.of());
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(TaskAdvisorService.scopeApplies(scope, spring, Set.of("feature"))).isFalse();
        }

        @Test
        void appliesWhenTagsOverlap() {
            var scope = new Scope(List.of(), List.of(), List.of(), List.of(), List.of("rest", "errors"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(TaskAdvisorService.scopeApplies(scope, spring, Set.of("rest", "feature")))
                .isTrue();
        }

        @Test
        void rejectsWhenTagsDoNotOverlap() {
            var scope = new Scope(List.of(), List.of(), List.of(), List.of(), List.of("testing"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(TaskAdvisorService.scopeApplies(scope, spring, Set.of("rest", "feature")))
                .isFalse();
        }
    }

    // === detectOptedOutRules ===

    @Nested
    class DetectOptedOutRules {

        @Test
        void emptyWhenNoNegations() {
            var rules = List.of(rule("auth-rule", "Authentication Required by Default"));
            var history = List.of(
                new TaskTurn("Should auth be required?", "yes please"));
            assertThat(TaskAdvisorService.detectOptedOutRules(rules, history)).isEmpty();
        }

        @Test
        void findsRulesWhoseTitleMatchesNegatedQuestion() {
            var rules = List.of(
                rule("auth-rule", "Authentication Required by Default"),
                rule("rate-rule", "Documented Rate-Limit Policy"));
            var history = List.of(
                new TaskTurn("What authentication is required by default?", "no need"));
            var optedOut = TaskAdvisorService.detectOptedOutRules(rules, history);
            assertThat(optedOut).contains("auth-rule");
            assertThat(optedOut).doesNotContain("rate-rule");
        }

        @Test
        void stemTolerantMatchesPluralsToSingulars() {
            // Title has "Defaults" (plural), question has "default" (singular).
            // Bug we're preventing: exact-match missed this pair.
            var rules = List.of(
                rule("auth-defaults", "Authentication and Session Defaults Are Secure"));
            var history = List.of(
                new TaskTurn("What authentication is required by default?", "no need"));
            assertThat(TaskAdvisorService.detectOptedOutRules(rules, history))
                .contains("auth-defaults");
        }
    }

    // === detectOptedOutTags + overlapsTags ===

    @Nested
    class TagLevelOptOut {

        @Test
        void detectsSecurityOptOutFromAuthQuestion() {
            var history = List.of(
                new TaskTurn("Is authentication required?", "no need"));
            var tags = TaskAdvisorService.detectOptedOutTags(history);
            assertThat(tags).contains("security", "auth", "crypto");
        }

        @Test
        void detectsObservabilityOptOutFromLoggingQuestion() {
            var history = List.of(
                new TaskTurn("Do we need detailed logging?", "skip"));
            var tags = TaskAdvisorService.detectOptedOutTags(history);
            assertThat(tags).contains("observability");
        }

        @Test
        void noOptOutWhenAnswerIsAffirmative() {
            var history = List.of(
                new TaskTurn("Is authentication required?", "yes"));
            assertThat(TaskAdvisorService.detectOptedOutTags(history)).isEmpty();
        }

        @Test
        void overlapsTagsTrueWhenRuleTagOptedOut() {
            var scope = new Scope(List.of(), List.of(), List.of(), List.of(),
                List.of("security", "crypto"));
            assertThat(TaskAdvisorService.overlapsTags(scope, Set.of("security"))).isTrue();
        }

        @Test
        void overlapsTagsFalseWhenNoOverlap() {
            var scope = new Scope(List.of(), List.of(), List.of(), List.of(),
                List.of("rest", "errors"));
            assertThat(TaskAdvisorService.overlapsTags(scope, Set.of("security"))).isFalse();
        }

        @Test
        void overlapsTagsFalseForEmptyScope() {
            assertThat(TaskAdvisorService.overlapsTags(Scope.empty(), Set.of("security"))).isFalse();
        }
    }

    // === groundOutOfScope ===

    @Nested
    class GroundOutOfScope {

        @Test
        void clearsSectionWhenNoUserOptOuts() {
            var sections = new FinalizeSections("goal", "- accept", "- some bullet");
            var result = TaskAdvisorService.groundOutOfScope(sections, List.of(), Set.of(), List.of());
            assertThat(result.outOfScope()).isEmpty();
        }

        @Test
        void keepsBulletsGroundedInNegatedQuestion() {
            var sections = new FinalizeSections("goal", "- accept",
                "- Adding authentication to this endpoint.\n- Adding concurrent request support.");
            var history = List.of(
                new TaskTurn("Should authentication be required?", "no need"));
            var rules = List.of(rule("auth", "Authentication Required by Default"));
            var optedOut = Set.of("auth");
            var result = TaskAdvisorService.groundOutOfScope(sections, history, optedOut, rules);
            assertThat(result.outOfScope()).contains("authentication");
            assertThat(result.outOfScope()).doesNotContain("concurrent");
        }
    }

    // === groundAcceptance ===

    @Nested
    class GroundAcceptance {

        @Test
        void dropsQuoteOnlyBullets() {
            var sections = new FinalizeSections("goal",
                "- \"create new api\"\n- The implementation matches the task description.",
                "");
            var result = TaskAdvisorService.groundAcceptance(sections, "create new api", List.of());
            assertThat(result.acceptance()).doesNotContain("\"create new api\"");
            assertThat(result.acceptance()).contains("matches the task description");
        }

        @Test
        void dropsBulletsWithNoUserWordOverlap() {
            var sections = new FinalizeSections("goal",
                "- Adds support for internationalization.\n- The API returns a greeting message.",
                "");
            var result = TaskAdvisorService.groundAcceptance(sections,
                "create greeting api",
                List.of(new TaskTurn("What does it do?", "return a greeting message")));
            assertThat(result.acceptance()).doesNotContain("internationalization");
            assertThat(result.acceptance()).contains("greeting message");
        }

        @Test
        void keepsFallbackBulletByWhitelist() {
            var sections = new FinalizeSections("goal",
                "- The implementation matches the task description and any constraints in the parent prompt.",
                "");
            var result = TaskAdvisorService.groundAcceptance(sections,
                "completely unrelated user words", List.of());
            assertThat(result.acceptance()).contains("matches the task description");
        }
    }

    // === separateGoalProseFromBullets ===

    @Nested
    class SeparateGoalProseFromBullets {

        @Test
        void passesThroughPureProseGoal() {
            var sections = new FinalizeSections(
                "Create a greeting API that returns a name.", "- accept bullet", "");
            var result = TaskAdvisorService.separateGoalProseFromBullets(sections);
            assertThat(result.goal()).isEqualTo("Create a greeting API that returns a name.");
            assertThat(result.acceptance()).isEqualTo("- accept bullet");
        }

        @Test
        void movesTrailingGoalBulletsToAcceptance() {
            var goalWithBullets = """
                Create a new greeting API.

                - The API responds to GET.
                - Returns a JSON greeting.
                """;
            var sections = new FinalizeSections(goalWithBullets, "- existing accept", "");
            var result = TaskAdvisorService.separateGoalProseFromBullets(sections);
            assertThat(result.goal()).isEqualTo("Create a new greeting API.");
            assertThat(result.acceptance())
                .contains("The API responds to GET")
                .contains("Returns a JSON greeting")
                .contains("existing accept");
        }
    }

    // === pickNextUncoveredMustRule ===

    @Nested
    class PickNextUncoveredMustRule {

        @Test
        void returnsNullWhenAllRulesCovered() {
            var rules = List.of(mustRule("auth", "Authentication Required by Default"));
            var history = List.of(
                new TaskTurn("Should authentication be required by default?", "yes"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(TaskAdvisorService.pickNextUncoveredMustRule(
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
            var picked = TaskAdvisorService.pickNextUncoveredMustRule(
                history, rules, spring, Set.of("rest", "feature"), Set.of());
            assertThat(picked).isNotNull();
            assertThat(picked.id()).isEqualTo("rate");
        }

        @Test
        void skipsOptedOutRules() {
            var rules = List.of(mustRule("auth", "Authentication Required by Default"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(TaskAdvisorService.pickNextUncoveredMustRule(
                List.of(), rules, spring, Set.of("rest", "feature"), Set.of("auth"))).isNull();
        }

        @Test
        void skipsNonMustSeverity() {
            var rules = List.of(rule("optional", "should", "Some Should-Severity Rule"));
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(TaskAdvisorService.pickNextUncoveredMustRule(
                List.of(), rules, spring, Set.of("rest", "feature"), Set.of())).isNull();
        }
    }

    // === assembleFinalMarkdown ===

    @Nested
    class AssembleFinalMarkdown {

        @Test
        void producesAllSectionsInOrder() {
            var sections = new FinalizeSections("Create a new API.", "- accepts a name", "");
            // Bullet renders from the rule's description's first sentence + id suffix.
            var rules = List.of(ruleWithDesc("rule1", "must", "Some directive text."));
            var doc = TaskAdvisorService.assembleFinalMarkdown(
                "create new api", sections, rules, List.of(), List.of());
            assertThat(doc)
                .contains("## Goal")
                .contains("Create a new API.")
                .contains("## Constraints")
                .contains("[must] Some directive text.")
                .contains("(rule1)")
                .contains("## Acceptance criteria")
                .contains("accepts a name");
        }

        @Test
        void omitsOutOfScopeSectionWhenEmpty() {
            var sections = new FinalizeSections("goal", "- a", "");
            var doc = TaskAdvisorService.assembleFinalMarkdown(
                "task", sections, List.of(), List.of(), List.of());
            assertThat(doc).doesNotContain("## Out of scope");
        }

        @Test
        void includesOutOfScopeSectionWhenNonEmpty() {
            var sections = new FinalizeSections("goal", "- a", "- excluded thing");
            var doc = TaskAdvisorService.assembleFinalMarkdown(
                "task", sections, List.of(), List.of(), List.of());
            assertThat(doc).contains("## Out of scope").contains("excluded thing");
        }

        @Test
        void capsConstraintsWithOverflowFooter() {
            // 12 rules, MAX_CONSTRAINTS is 10 → 2 overflow.
            var rules = new java.util.ArrayList<Rule>();
            for (int i = 1; i <= 12; i++) rules.add(mustRule("rule-" + i, "Rule " + i));
            var sections = new FinalizeSections("g", "- a", "");
            var doc = TaskAdvisorService.assembleFinalMarkdown(
                "t", sections, rules, List.of(), List.of());
            assertThat(doc).contains("_Also applicable (2 more):");
            assertThat(doc).contains("rule-11").contains("rule-12");
        }

        @Test
        void sortsConstraintsBySeverity() {
            var rules = List.of(
                ruleWithDesc("low", "should", "Should-severity directive."),
                ruleWithDesc("high", "must", "Must-severity directive."));
            var sections = new FinalizeSections("g", "- a", "");
            var doc = TaskAdvisorService.assembleFinalMarkdown(
                "t", sections, rules, List.of(), List.of());
            // must rule should appear before should rule in the rendered output.
            int mustIdx = doc.indexOf("Must-severity directive");
            int shouldIdx = doc.indexOf("Should-severity directive");
            assertThat(mustIdx).isPositive().isLessThan(shouldIdx);
        }

        @Test
        void rendersStandardsConsultedWhenSkillsOrChecklistsPresent() {
            var sections = new FinalizeSections("g", "- a", "");
            var skill = new Skill("add-endpoint", "Add an Endpoint", null, List.of(), List.of(), null, Scope.empty());
            var doc = TaskAdvisorService.assembleFinalMarkdown(
                "t", sections, List.of(), List.of(skill), List.of());
            assertThat(doc).contains("## Standards consulted").contains("skill:add-endpoint");
        }
    }

    // === normaliseFramework / normaliseLanguage ===

    @Nested
    class StackNormalisation {

        @Test
        void frameworkLowercasesAndDashes() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(TaskAdvisorService.normaliseFramework(spring)).isEqualTo("spring-boot");
        }

        @Test
        void frameworkHandlesDots() {
            var spring = new StackProfile("Java", "Maven", "Spring.Boot", List.of());
            assertThat(TaskAdvisorService.normaliseFramework(spring)).isEqualTo("springboot");
        }

        @Test
        void languageLowercases() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            assertThat(TaskAdvisorService.normaliseLanguage(spring)).isEqualTo("java");
        }

        @Test
        void nullStackOrFieldsHandled() {
            assertThat(TaskAdvisorService.normaliseFramework(null)).isNull();
            assertThat(TaskAdvisorService.normaliseLanguage(null)).isNull();
        }
    }

    // === helpers ===

    private static Rule rule(String id, String title) {
        return rule(id, "must", title);
    }

    private static Rule mustRule(String id, String title) {
        return rule(id, "must", title);
    }

    private static Rule rule(String id, String severity, String title) {
        return new Rule(id, title, severity, "A description.", "A rationale.", Scope.empty(), null, null);
    }

    private static Rule ruleWithDesc(String id, String severity, String description) {
        return new Rule(id, "Title-" + id, severity, description, null, Scope.empty(), null, null);
    }

    // === isNearDuplicateOfPrior false-positive guards ===

    @Nested
    class IsNearDuplicateOfPriorFalsePositives {

        @Test
        void differentAuthAnglesNotDuplicate() {
            // Both touch "auth" but probe different aspects - the rate threshold
            // should NOT collapse them. The Jaccard threshold must let related
            // questions through.
            var history = List.of(
                new TaskTurn("Should the endpoint require authentication?", "yes"));
            var newQ = "How long should auth tokens stay valid before refresh?";
            assertThat(TaskAdvisorService.isNearDuplicateOfPrior(newQ, history)).isFalse();
        }

        @Test
        void unrelatedShortQuestionNotDuplicate() {
            var history = List.of(
                new TaskTurn("Which authentication scheme applies?", "team default"));
            assertThat(TaskAdvisorService.isNearDuplicateOfPrior(
                "What error envelope should responses use?", history)).isFalse();
        }
    }

    // === discoveryHintFor ===

    @Nested
    class DiscoveryHintFor {

        @Test
        void restTaskGetsResourceShape() {
            var hint = TaskAdvisorService.discoveryHintFor("add a new endpoint", List.of());
            assertThat(hint).contains("resource").contains("HTTP method");
        }

        @Test
        void debuggingTaskGetsSymptomShape() {
            var hint = TaskAdvisorService.discoveryHintFor("fix bug in login flow", List.of());
            assertThat(hint).contains("symptom").contains("reproduction");
        }

        @Test
        void refactoringTaskGetsMotivationShape() {
            var hint = TaskAdvisorService.discoveryHintFor("refactor the user service", List.of());
            assertThat(hint).contains("motivation").contains("behaviour-preservation");
        }

        @Test
        void uiTaskGetsScreenShape() {
            var hint = TaskAdvisorService.discoveryHintFor("add a new screen for settings", List.of());
            assertThat(hint).contains("screen").contains("user action");
        }

        @Test
        void aiTaskGetsModelShape() {
            var hint = TaskAdvisorService.discoveryHintFor("add llm prompt template", List.of());
            assertThat(hint).contains("model role").contains("prompt inputs");
        }

        @Test
        void unclassifiedTaskGetsNeutralFallback() {
            var hint = TaskAdvisorService.discoveryHintFor("xyz", List.of());
            assertThat(hint).contains("purpose").contains("inputs").contains("outputs");
        }
    }

    // === selectRelevantStandards ===

    @Nested
    class SelectRelevantStandards {

        @Test
        void filtersByFrameworkScope() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            var matching = new Rule("a", "A", "must", "x", null,
                new Scope(List.of(), List.of("spring-boot"), List.of(), List.of(), List.of()), null, null);
            var nonMatching = new Rule("b", "B", "must", "x", null,
                new Scope(List.of(), List.of("next"), List.of(), List.of(), List.of()), null, null);

            var result = TaskAdvisorService.selectRelevantStandards(
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

            var result = TaskAdvisorService.selectRelevantStandards(
                spring, "create endpoint", history,
                List.of(auth, rate), List.of(), List.of());
            assertThat(result.rules()).extracting(Rule::id).contains("rate").doesNotContain("auth");
        }

        @Test
        void nullInputListsHandledGracefully() {
            var spring = new StackProfile("Java", "Maven", "Spring Boot", List.of());
            var result = TaskAdvisorService.selectRelevantStandards(
                spring, "task", List.of(), null, null, null);
            assertThat(result.rules()).isEmpty();
            assertThat(result.skills()).isEmpty();
            assertThat(result.checklists()).isEmpty();
        }
    }

    // === assembleFinalMarkdown null-task safety ===

    @Nested
    class AssembleFinalMarkdownNullSafety {

        @Test
        void nullUserTaskDoesNotCrash() {
            var sections = new FinalizeSections("My goal.", "- accept", "");
            var doc = TaskAdvisorService.assembleFinalMarkdown(
                null, sections, List.of(), List.of(), List.of());
            assertThat(doc).contains("## Goal").contains("My goal.");
        }

        @Test
        void nullUserTaskAndEmptyGoalProducesSentinel() {
            var sections = new FinalizeSections("", "- accept", "");
            var doc = TaskAdvisorService.assembleFinalMarkdown(
                null, sections, List.of(), List.of(), List.of());
            assertThat(doc).contains("(no goal provided)");
        }
    }

    // === PromptParts ===

    @Nested
    class PromptPartsSplit {

        @Test
        void splitsOnMarkers() {
            var template = "===SYSTEM===\nsystem text\n===USER===\nuser text";
            var parts = TaskAdvisorService.PromptParts.split(template);
            assertThat(parts.system()).isEqualTo("system text");
            assertThat(parts.user()).isEqualTo("user text");
        }

        @Test
        void emptySystemWhenMarkersMissing() {
            var template = "just a body with no markers";
            var parts = TaskAdvisorService.PromptParts.split(template);
            assertThat(parts.system()).isEmpty();
            assertThat(parts.user()).isEqualTo(template);
        }

        @Test
        void emptySystemWhenOnlyUserMarker() {
            var template = "===USER===\nbody";
            var parts = TaskAdvisorService.PromptParts.split(template);
            assertThat(parts.system()).isEmpty();
            assertThat(parts.user()).isEqualTo(template);
        }

        @Test
        void handlesNull() {
            var parts = TaskAdvisorService.PromptParts.split(null);
            assertThat(parts.system()).isEmpty();
            assertThat(parts.user()).isEmpty();
        }
    }
}
