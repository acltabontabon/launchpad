package com.acltabontabon.launchpad.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders findings as {@code .launchpad/audit.md}, grouped by severity in the
 * order tech leads care about first ({@code never} > {@code must} > {@code should}
 * > {@code avoid}). The companion SARIF document is the machine-readable form;
 * this one is for humans skimming the TUI or reading the file in a PR.
 */
@Component
public class MarkdownAuditWriter {

    private static final List<String> SEVERITY_ORDER = List.of("never", "must", "should", "avoid");

    public Path write(Path projectRoot, List<Finding> findings) {
        var target = projectRoot.resolve(".launchpad").resolve("audit.md");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, render(findings));
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }

    String render(List<Finding> findings) {
        var sb = new StringBuilder();
        sb.append("# Standards Audit\n\n");
        sb.append("Generated ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        if (findings.isEmpty()) {
            sb.append("No violations found. \n");
            return sb.toString();
        }

        var counts = countBySeverity(findings);
        sb.append("**Summary:** ");
        sb.append(counts.entrySet().stream()
            .map(e -> e.getValue() + " " + e.getKey())
            .reduce((a, b) -> a + ", " + b)
            .orElse("none"));
        sb.append("\n\n");

        var grouped = groupBySeverity(findings);
        for (var severity : SEVERITY_ORDER) {
            var bucket = grouped.get(severity);
            if (bucket == null || bucket.isEmpty()) continue;
            sb.append("## ").append(capitalize(severity)).append(" (").append(bucket.size()).append(")\n\n");
            for (var f : bucket) {
                sb.append("- **").append(f.ruleId()).append("** - ").append(f.ruleTitle()).append("\n");
                if (f.filePath() != null) {
                    sb.append("  - `").append(f.filePath());
                    if (f.line() != null) sb.append(":").append(f.line());
                    sb.append("`\n");
                }
                sb.append("  - ").append(f.message()).append("\n");
                if (f.evidence() != null && !f.evidence().isBlank()) {
                    sb.append("  - `").append(f.evidence()).append("`\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private Map<String, Integer> countBySeverity(List<Finding> findings) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var severity : SEVERITY_ORDER) counts.put(severity, 0);
        for (var f : findings) {
            var key = f.severity() == null ? "should" : f.severity().toLowerCase();
            counts.merge(key, 1, Integer::sum);
        }
        counts.values().removeIf(v -> v == 0);
        return counts;
    }

    private Map<String, List<Finding>> groupBySeverity(List<Finding> findings) {
        var grouped = new LinkedHashMap<String, List<Finding>>();
        for (var severity : SEVERITY_ORDER) grouped.put(severity, new java.util.ArrayList<>());
        for (var f : findings) {
            var key = f.severity() == null ? "should" : f.severity().toLowerCase();
            grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(f);
        }
        return grouped;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
