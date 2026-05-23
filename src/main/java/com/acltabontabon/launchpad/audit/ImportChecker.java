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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Flags forbidden imports (Java {@code import ...;} and Python {@code from ... import}
 * / {@code import ...}). Each rule's {@code check.imports} list contains FQN prefixes,
 * with trailing {@code .**} meaning "any sub-package". Optional {@code check.inPackages}
 * restricts which source paths are inspected (also globs against the relative path).
 */
@Component
public class ImportChecker implements RuleChecker {

    private static final long MAX_FILE_BYTES = 1_000_000L;
    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.$*]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PY_IMPORT = Pattern.compile("^\\s*(?:from\\s+([\\w.]+)\\s+import|import\\s+([\\w.]+))", Pattern.MULTILINE);

    @Override
    public String kind() {
        return "forbid-import";
    }

    @Override
    public List<Finding> check(Rule rule, ProjectContext ctx, Path projectRoot) {
        var check = rule.check();
        if (check == null || check.imports().isEmpty()) {
            return List.of();
        }
        var findings = new ArrayList<Finding>();
        for (var relativePath : ctx.sourceFiles()) {
            if (!matchesInPackages(relativePath, check)) {
                continue;
            }
            var absolute = projectRoot.resolve(relativePath);
            var content = safeRead(absolute);
            if (content == null) {
                continue;
            }
            findings.addAll(scanFile(rule, relativePath, content));
        }
        return findings;
    }

    private List<Finding> scanFile(Rule rule, String relativePath, String content) {
        var out = new ArrayList<Finding>();
        var pattern = relativePath.endsWith(".py") ? PY_IMPORT : JAVA_IMPORT;
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            var imported = matcher.group(1) != null ? matcher.group(1) : matcher.groupCount() >= 2 ? matcher.group(2) : null;
            if (imported == null) continue;
            for (var forbidden : rule.check().imports()) {
                if (matchesFqn(imported, forbidden)) {
                    int line = lineNumberAt(content, matcher.start());
                    out.add(Finding.at(
                        rule.id(),
                        rule.severity(),
                        rule.title(),
                        relativePath,
                        line,
                        "Forbidden import: " + imported,
                        matcher.group().strip()
                    ));
                    break;
                }
            }
        }
        return out;
    }

    static boolean matchesFqn(String imported, String pattern) {
        if (pattern.endsWith(".**")) {
            var prefix = pattern.substring(0, pattern.length() - 3);
            return imported.equals(prefix) || imported.startsWith(prefix + ".");
        }
        if (pattern.endsWith(".*")) {
            var prefix = pattern.substring(0, pattern.length() - 2);
            if (!imported.startsWith(prefix + ".")) return false;
            return !imported.substring(prefix.length() + 1).contains(".");
        }
        return imported.equals(pattern);
    }

    private static boolean matchesInPackages(String relativePath, Check check) {
        if (check.inPackages().isEmpty()) {
            return true;
        }
        for (var glob : check.inPackages()) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            if (matcher.matches(Path.of(relativePath))) {
                return true;
            }
        }
        return false;
    }

    private static int lineNumberAt(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
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
