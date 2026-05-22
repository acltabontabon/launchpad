package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks LLM output for the obvious failure modes:
 *   1. Empty / suspiciously short content
 *   2. Missing structural markers the prompt asked for (## headings)
 *   3. Backtick-quoted file paths that don't exist in the scanned project
 *      (hallucination - common with smaller local models)
 * Returns a list of human-readable warnings. Empty list = passed.
 */
public final class OutputValidator {

    private static final int MIN_CONTENT_CHARS = 120;
    private static final int MAX_HALLUCINATION_WARNINGS = 5;
    private static final Pattern BACKTICKED_PATH = Pattern.compile(
        "`([A-Za-z0-9_\\-./]+\\.(java|kt|py|ts|tsx|js|jsx|go|rs|cs|rb|swift|xml|yml|yaml|toml|json|md|properties))`");

    public List<String> validate(String content, ProjectContext ctx, List<String> requiredHeadings) {
        var warnings = new ArrayList<String>();
        if (content == null || content.isBlank()) {
            warnings.add("empty model output");
            return warnings;
        }
        if (content.length() < MIN_CONTENT_CHARS) {
            warnings.add("suspiciously short output (" + content.length() + " chars)");
        }
        for (String heading : requiredHeadings) {
            if (!content.contains(heading)) {
                warnings.add("missing required section: " + heading);
            }
        }
        addHallucinationWarnings(content, ctx, warnings);
        return warnings;
    }

    private static void addHallucinationWarnings(String content, ProjectContext ctx, List<String> warnings) {
        Set<String> sourceSet = new HashSet<>(ctx.sourceFiles());
        Set<String> basenames = new HashSet<>();
        for (var p : ctx.sourceFiles()) {
            int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
            basenames.add(slash < 0 ? p : p.substring(slash + 1));
        }
        // Also accept declared dep names and build files as legitimate references.
        for (var d : ctx.dependencies()) basenames.add(d.name());
        basenames.add("CLAUDE.md");
        basenames.add(".cursorrules");
        basenames.add("README.md");

        Matcher m = BACKTICKED_PATH.matcher(content);
        var seen = new HashSet<String>();
        int reported = 0;
        while (m.find() && reported < MAX_HALLUCINATION_WARNINGS) {
            String ref = m.group(1);
            if (!seen.add(ref)) continue;
            String basename = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
            if (sourceSet.contains(ref) || basenames.contains(basename)) continue;
            warnings.add("model referenced `" + ref + "` which is not in the scanned project");
            reported++;
        }
    }
}
