package com.acltabontabon.launchpad.audit;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drives the audit pass: loads auditable rules, dispatches each to the
 * {@link RuleChecker} matching its {@code check.kind}, and writes the SARIF + Markdown
 * reports under {@code .launchpad/}. Doc-only rules (no {@code check} block) are
 * skipped without warning.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final StandardsLoader standardsLoader;
    private final Map<String, RuleChecker> checkersByKind;
    private final SarifWriter sarifWriter;
    private final MarkdownAuditWriter markdownWriter;
    private final LaunchpadSettings settings;

    public AuditService(StandardsLoader standardsLoader,
                        List<RuleChecker> checkers,
                        SarifWriter sarifWriter,
                        MarkdownAuditWriter markdownWriter,
                        LaunchpadSettings settings) {
        this.standardsLoader = standardsLoader;
        this.checkersByKind = checkers.stream().collect(Collectors.toMap(RuleChecker::kind, c -> c));
        this.sarifWriter = sarifWriter;
        this.markdownWriter = markdownWriter;
        this.settings = settings;
    }

    /** Runs the audit. {@code progress} is invoked once per rule for TUI updates. */
    public AuditResult run(ProjectContext ctx, Path projectRoot, Consumer<RuleProgress> progress) {
        var allRules = standardsLoader.loadRules(projectRoot);
        var auditable = allRules.stream().filter(Rule::isAuditable).toList();
        if (auditable.isEmpty()) {
            return AuditResult.empty();
        }

        boolean llmChecksEnabled = settings.snapshot().enableLlmChecks();
        var findings = new ArrayList<Finding>();
        for (int i = 0; i < auditable.size(); i++) {
            var rule = auditable.get(i);
            progress.accept(new RuleProgress(i + 1, auditable.size(), rule.id()));
            var checker = checkersByKind.get(rule.check().kind());
            if (checker == null) {
                log.warn("No RuleChecker registered for kind '{}' (rule {}); skipping.",
                    rule.check().kind(), rule.id());
                continue;
            }
            if ("llm".equals(rule.check().kind()) && !llmChecksEnabled) {
                log.warn("rule {} requires LLM audit checks - enable launchpad.audit.enable-llm-checks to evaluate.",
                    rule.id());
                continue;
            }
            try {
                findings.addAll(checker.check(rule, ctx, projectRoot));
            } catch (RuntimeException e) {
                log.warn("Rule {} failed during audit: {}", rule.id(), e.getMessage());
            }
        }

        var sarifPath = sarifWriter.write(projectRoot, auditable, findings);
        var markdownPath = markdownWriter.write(projectRoot, findings);
        return new AuditResult(findings, auditable.size(), sarifPath, markdownPath);
    }

    public AuditResult run(ProjectContext ctx, Path projectRoot) {
        return run(ctx, projectRoot, p -> { });
    }

    public record RuleProgress(int index, int total, String ruleId) {}

    public record AuditResult(List<Finding> findings, int rulesAudited, Path sarifPath, Path markdownPath) {
        public static AuditResult empty() {
            return new AuditResult(List.of(), 0, null, null);
        }

        public Map<String, Long> countsBySeverity() {
            return findings.stream()
                .collect(Collectors.groupingBy(
                    f -> f.severity() == null ? "should" : f.severity().toLowerCase(),
                    Collectors.counting()));
        }
    }
}
