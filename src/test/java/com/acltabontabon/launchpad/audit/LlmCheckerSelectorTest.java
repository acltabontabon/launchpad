package com.acltabontabon.launchpad.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Check;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Selector tests verify the file-matching logic without invoking a ChatClient.
 * The model-call path is verified by the eval-profile integration test against
 * a live Ollama.
 */
class LlmCheckerSelectorTest {

    @Test
    void controllersSelectorMatchesByClassSuffix() {
        var picked = LlmCheckerSelector.select(ruleWithTargets("controllers"),
            List.of(
                "src/main/java/api/UserController.java",
                "src/main/java/api/UserService.java",
                "src/main/java/api/UserConfig.java"),
            20);

        assertThat(picked).containsExactly("src/main/java/api/UserController.java");
    }

    @Test
    void servicesSelectorMatchesByClassSuffix() {
        var picked = LlmCheckerSelector.select(ruleWithTargets("services"),
            List.of(
                "src/main/java/api/UserController.java",
                "src/main/java/api/UserService.java"),
            20);

        assertThat(picked).containsExactly("src/main/java/api/UserService.java");
    }

    @Test
    void configsSelectorMatchesConfigPathOrClassSuffix() {
        var picked = LlmCheckerSelector.select(ruleWithTargets("configs"),
            List.of(
                "src/main/java/config/AppConfig.java",
                "src/main/java/MyConfiguration.java",
                "src/main/java/api/UserController.java"),
            20);

        assertThat(picked).containsExactlyInAnyOrder(
            "src/main/java/config/AppConfig.java",
            "src/main/java/MyConfiguration.java");
    }

    @Test
    void filesMatchingFallsBackToIncludesGlobs() {
        var rule = new Rule("r", "t", "must", "d", null, Scope.empty(), 1,
            new Check("llm", null, List.of("**/*.py"), null, null, null, "files-matching", "Q?"));

        var picked = LlmCheckerSelector.select(rule,
            List.of("src/main/java/User.java", "src/main/python/user.py"),
            20);

        assertThat(picked).containsExactly("src/main/python/user.py");
    }

    @Test
    void capsAtMaxFiles() {
        var picked = LlmCheckerSelector.select(ruleWithTargets("controllers"),
            List.of("a/AController.java", "b/BController.java", "c/CController.java"),
            2);

        assertThat(picked).hasSize(2);
    }

    private static Rule ruleWithTargets(String targets) {
        return new Rule("r", "Thin Controllers", "must", "d", null, Scope.empty(), 1,
            new Check("llm", null, null, null, null, null, targets, "Q?"));
    }
}
