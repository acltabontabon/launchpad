package com.acltabontabon.launchpad.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acltabontabon.launchpad.ai.LlmProvider;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.scanner.PackageSummary;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Check;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the issue-40 contract: LLM-backed audit rules only run when the user
 * has flipped the opt-in flag. Deterministic checkers (pattern / import) keep
 * running regardless so the default scan experience stays useful.
 */
class AuditServiceLlmOptInTest {

    @Test
    void skipsLlmRulesWhenFlagIsOff(@TempDir Path projectRoot) {
        var llmChecker = mock(RuleChecker.class);
        when(llmChecker.kind()).thenReturn("llm");
        var patternChecker = mock(RuleChecker.class);
        when(patternChecker.kind()).thenReturn("forbid-pattern");
        when(patternChecker.check(any(), any(), any())).thenReturn(List.of());

        var standardsLoader = mock(StandardsLoader.class);
        when(standardsLoader.loadRules(projectRoot)).thenReturn(List.of(
            ruleWithCheck("llm-rule", new Check("llm", null, null, null, null, null, "services",
                "Does this service log secrets?")),
            ruleWithCheck("pattern-rule", new Check("forbid-pattern", "@Autowired",
                List.of("**/*.java"), null, null, null, null, null))
        ));

        var auditService = new AuditService(
            standardsLoader,
            List.of(llmChecker, patternChecker),
            new SarifWriter(),
            new MarkdownAuditWriter(),
            settingsWith(false));

        auditService.run(emptyContext(), projectRoot);

        verify(llmChecker, never()).check(any(), any(), any());
        verify(patternChecker, times(1)).check(any(), any(), any());
    }

    @Test
    void runsLlmRulesWhenFlagIsOn(@TempDir Path projectRoot) {
        var llmChecker = mock(RuleChecker.class);
        when(llmChecker.kind()).thenReturn("llm");
        when(llmChecker.check(any(), any(), any())).thenReturn(List.of());

        var standardsLoader = mock(StandardsLoader.class);
        when(standardsLoader.loadRules(projectRoot)).thenReturn(List.of(
            ruleWithCheck("llm-rule", new Check("llm", null, null, null, null, null, "services",
                "Does this service log secrets?"))
        ));

        var auditService = new AuditService(
            standardsLoader,
            List.of(llmChecker),
            new SarifWriter(),
            new MarkdownAuditWriter(),
            settingsWith(true));

        auditService.run(emptyContext(), projectRoot);

        verify(llmChecker, times(1)).check(any(), any(), any());
    }

    private static LaunchpadSettings settingsWith(boolean enableLlmChecks) {
        var settings = mock(LaunchpadSettings.class);
        when(settings.snapshot()).thenReturn(new Snapshot(
            LlmProvider.AUTO, "http://localhost:11434", "any-model",
            null, null, null, enableLlmChecks));
        return settings;
    }

    private static Rule ruleWithCheck(String id, Check check) {
        return new Rule(id, id, "must", "desc", "rationale",
            Scope.empty(), 10, check);
    }

    private static ProjectContext emptyContext() {
        return new ProjectContext(
            "demo", "/tmp/demo",
            new StackProfile("Java", "Maven", "Spring Boot", List.of()),
            List.of(), List.of(), Map.of(),
            List.of(new Dependency("x", "1", "runtime")),
            Map.of(), List.<PackageSummary>of(), null);
    }
}
