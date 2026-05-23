package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Result of a project scan. Carries a structured stack profile, dependency
 * list, package-grouped structure summary, entry points, and short excerpts
 * of key build / config files.
 * <p>
 * `toPromptString` renders a token-budget-aware view for the LLM - prefer it
 * over assembling prompt content from raw fields, so the budget cap holds.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectContext(
    String name,
    String rootPath,
    StackProfile stack,
    List<String> sourceFiles,            // full list - used for hallucination check, not the prompt
    List<String> testClassNames,
    Map<String, String> entryPoints,
    List<Dependency> dependencies,
    Map<String, String> fileSnippets,    // key build / config file → first N lines
    List<PackageSummary> packageSummaries,
    String existingContextSummary        // first ~800 chars of existing CLAUDE.md / .cursorrules, or null
) {

    /** Approximate character budget for the prompt-side rendering of this context. */
    private static final int DEFAULT_BUDGET_CHARS = 8_000;

    public String toPromptString() {
        return toPromptString(DEFAULT_BUDGET_CHARS);
    }

    public String toPromptString(int budgetChars) {
        var sb = new StringBuilder();

        sb.append("# Project: ").append(name).append("\n");
        sb.append("Stack: ").append(stack.displayName()).append("\n");
        if (stack.framework() != null) {
            sb.append("Framework: ").append(stack.framework()).append("\n");
        }
        sb.append("Source file count: ").append(sourceFiles.size()).append("\n");
        if (!testClassNames.isEmpty()) {
            sb.append("Test file count: ").append(testClassNames.size()).append("\n");
        }
        sb.append("\n");

        if (!entryPoints.isEmpty()) {
            sb.append("## Entry Points\n");
            entryPoints.forEach((k, v) -> sb.append("- ").append(k).append(": `").append(v).append("`\n"));
            sb.append("\n");
        }

        if (!packageSummaries.isEmpty()) {
            sb.append("## Source Structure (top packages by file count)\n");
            for (var pkg : packageSummaries) {
                sb.append("- `").append(pkg.path()).append("/` (").append(pkg.fileCount()).append(" files)");
                if (!pkg.sampleSymbols().isEmpty()) {
                    sb.append(" - ").append(String.join(", ", pkg.sampleSymbols()));
                }
                sb.append("\n");
                if (sb.length() > budgetChars * 6 / 10) break;
            }
            sb.append("\n");
        }

        if (!dependencies.isEmpty()) {
            sb.append("## Dependencies (").append(dependencies.size()).append(" total)\n");
            int limit = Math.min(dependencies.size(), 40);
            for (int i = 0; i < limit; i++) {
                sb.append("- ").append(dependencies.get(i).display()).append("\n");
            }
            if (dependencies.size() > limit) {
                sb.append("- ... and ").append(dependencies.size() - limit).append(" more\n");
            }
            sb.append("\n");
        }

        if (existingContextSummary != null && !existingContextSummary.isBlank()
            && sb.length() < budgetChars - 600) {
            sb.append("## Existing Context (already documented - focus on what's missing, don't duplicate)\n\n```\n");
            sb.append(truncate(existingContextSummary, 600));
            sb.append("\n```\n\n");
        }

        if (!fileSnippets.isEmpty() && sb.length() < budgetChars - 500) {
            sb.append("## Key File Excerpts\n");
            for (var entry : fileSnippets.entrySet()) {
                if (sb.length() > budgetChars - 300) break;
                sb.append("\n### ").append(entry.getKey()).append("\n```\n");
                sb.append(truncate(entry.getValue(), Math.min(800, budgetChars - sb.length() - 200)));
                sb.append("\n```\n");
            }
        }

        if (sb.length() > budgetChars) {
            sb.setLength(budgetChars);
            sb.append("\n... (truncated to fit prompt budget)\n");
        }
        return sb.toString();
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, Math.max(0, maxChars)) + "\n... (truncated)";
    }
}
