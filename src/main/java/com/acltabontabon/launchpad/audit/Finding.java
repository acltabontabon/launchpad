package com.acltabontabon.launchpad.audit;

/**
 * One audit hit. {@code filePath} and {@code line} are nullable for rules that
 * fail at a project-wide level (e.g. missing required dependency). {@code evidence}
 * carries a short excerpt of the offending source line so reports are skim-friendly.
 */
public record Finding(
    String ruleId,
    String severity,
    String ruleTitle,
    String filePath,
    Integer line,
    String message,
    String evidence
) {
    public static Finding at(String ruleId, String severity, String ruleTitle,
                             String filePath, int line, String message, String evidence) {
        return new Finding(ruleId, severity, ruleTitle, filePath, line, message, evidence);
    }
}
