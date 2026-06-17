package com.acltabontabon.launchpad.audit;

/**
 * One audit hit. {@code filePath} and {@code line} are nullable for rules that
 * fail at a project-wide level (e.g. missing required dependency). {@code evidence}
 * carries a short excerpt of the offending source line so reports are skim-friendly.
 *
 * <p>{@code ruleHash} is the content hash of the exact rule record version the
 * finding was produced against. It equals {@code standards.index.json}
 * {@code rules[*].contentHash} for the same {@code ruleId}, so a consumer can
 * compare the two to detect findings produced against stale standards text.
 * Checkers do not set it - {@code AuditService} stamps it via {@link #withRuleHash}
 * after a checker returns, so the 7-arg {@link #at} factory stays hash-agnostic.
 */
public record Finding(
    String ruleId,
    String severity,
    String ruleTitle,
    String filePath,
    Integer line,
    String message,
    String evidence,
    String ruleHash
) {
    public static Finding at(String ruleId, String severity, String ruleTitle,
                             String filePath, int line, String message, String evidence) {
        return new Finding(ruleId, severity, ruleTitle, filePath, line, message, evidence, null);
    }

    /** Returns a copy with {@code ruleHash} set; used by {@code AuditService} to stamp findings. */
    public Finding withRuleHash(String ruleHash) {
        return new Finding(ruleId, severity, ruleTitle, filePath, line, message, evidence, ruleHash);
    }
}
