package com.acltabontabon.launchpad.audit;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Check;
import com.acltabontabon.launchpad.standards.Rule;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Runs a regex against the contents of every selected file and emits one
 * {@link Finding} per match (line-attributed, with a short evidence excerpt).
 * <p>
 * Selectors:
 * <ul>
 *   <li>{@code check.includes} - glob patterns matched against the project-relative file path.
 *       Defaults to "all source files" when empty.</li>
 *   <li>{@code check.excludes} - glob patterns subtracted from the selection.</li>
 * </ul>
 * The regex is compiled with {@link Pattern#MULTILINE} so {@code ^} / {@code $}
 * anchor per-line, matching the YAML authoring style most rule writers expect.
 */
@Component
public class PatternChecker implements RuleChecker {

    private static final long MAX_FILE_BYTES = 1_000_000L;

    @Override
    public String kind() {
        return "forbid-pattern";
    }

    @Override
    public List<Finding> check(Rule rule, ProjectContext ctx, Path projectRoot) {
        var check = rule.check();
        if (check == null || check.pattern() == null || check.pattern().isBlank()) {
            return List.of();
        }
        var compiled = Pattern.compile(check.pattern(), Pattern.MULTILINE);
        var findings = new ArrayList<Finding>();
        for (var relativePath : ctx.sourceFiles()) {
            if (!matchesSelectors(relativePath, check)) {
                continue;
            }
            var absolute = projectRoot.resolve(relativePath);
            var content = safeRead(absolute);
            if (content == null) {
                continue;
            }
            findings.addAll(matchesAsFindings(compiled, rule, relativePath, content));
        }
        return findings;
    }

    static boolean matchesSelectors(String relativePath, Check check) {
        if (!check.includes().isEmpty() && !anyMatch(relativePath, check.includes())) {
            return false;
        }
        return !anyMatch(relativePath, check.excludes());
    }

    private static boolean anyMatch(String relativePath, List<String> globs) {
        for (var glob : globs) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            if (matcher.matches(Path.of(relativePath))) {
                return true;
            }
        }
        return false;
    }

    private static List<Finding> matchesAsFindings(Pattern pattern, Rule rule,
                                                   String relativePath, String content) {
        var out = new ArrayList<Finding>();
        var matcher = pattern.matcher(content);
        while (matcher.find()) {
            int line = lineNumberAt(content, matcher.start());
            out.add(Finding.at(
                rule.id(),
                rule.severity(),
                rule.title(),
                relativePath,
                line,
                "Forbidden pattern matched.",
                excerpt(content, line)
            ));
        }
        return out;
    }

    private static int lineNumberAt(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String excerpt(String content, int line) {
        var lines = content.split("\n", -1);
        if (line - 1 < 0 || line - 1 >= lines.length) return "";
        var raw = lines[line - 1].strip();
        return raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
    }

    private static String safeRead(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
                return null;
            }
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }
}
