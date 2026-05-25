package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.scanner.PackageSummary;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the project's top packages as a bounded, fenced markdown tree
 * block. Used inside the deterministic `## Project map` section of the
 * generated AGENTS.md.
 * <p>
 * Bounded to {@link #MAX_LINES} entries so it stays readable on large
 * projects; sample symbols (already truncated by `StructureSummarizer`) are
 * appended as a comment-style annotation next to each package leaf.
 */
public final class FileTreeRenderer {

    private static final int MAX_LINES = 20;

    private FileTreeRenderer() {}

    public static String render(List<PackageSummary> packages) {
        if (packages == null || packages.isEmpty()) {
            return "_(no source packages detected)_\n";
        }
        var lines = build(packages);
        var sb = new StringBuilder();
        sb.append("```\n");
        for (var line : lines) {
            sb.append(line).append("\n");
        }
        sb.append("```\n");
        return sb.toString();
    }

    private static List<String> build(List<PackageSummary> packages) {
        var out = new ArrayList<String>();
        int shown = 0;
        for (var pkg : packages) {
            if (shown >= MAX_LINES) {
                out.add("... and " + (packages.size() - shown) + " more");
                break;
            }
            var path = pkg.path();
            var line = new StringBuilder(path).append("/");
            var symbols = pkg.sampleSymbols();
            if (symbols != null && !symbols.isEmpty()) {
                var preview = symbols.size() > 4
                    ? String.join(", ", symbols.subList(0, 4)) + ", ..."
                    : String.join(", ", symbols);
                line.append("  # ").append(preview);
            } else if (pkg.fileCount() > 0) {
                line.append("  # ").append(pkg.fileCount()).append(" files");
            }
            out.add(line.toString());
            shown++;
        }
        return out;
    }
}
