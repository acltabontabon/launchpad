package com.acltabontabon.launchpad.audit;

import com.acltabontabon.launchpad.standards.Check;
import com.acltabontabon.launchpad.standards.Rule;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * File selection for the LLM checker, extracted so it can be unit-tested
 * without constructing a {@code ChatClient}.
 */
final class LlmCheckerSelector {

    private LlmCheckerSelector() {}

    static List<String> select(Rule rule, List<String> sourceFiles, int maxFiles) {
        var matcher = matcherFor(rule.check());
        var out = new ArrayList<String>();
        for (var file : sourceFiles) {
            if (matcher.matches(file)) {
                out.add(file);
                if (out.size() >= maxFiles) break;
            }
        }
        return out;
    }

    private static SelectorMatcher matcherFor(Check check) {
        var targets = check.targets();
        if (targets == null || targets.isBlank() || "files-matching".equals(targets)) {
            return new GlobMatcher(check.includes());
        }
        return switch (targets) {
            case "controllers" -> new SuffixMatcher(List.of("Controller"));
            case "services" -> new SuffixMatcher(List.of("Service"));
            case "configs" -> new ConfigMatcher();
            default -> new GlobMatcher(check.includes());
        };
    }

    private interface SelectorMatcher {
        boolean matches(String relativePath);
    }

    private record SuffixMatcher(List<String> classSuffixes) implements SelectorMatcher {
        @Override
        public boolean matches(String relativePath) {
            int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
            int dot = relativePath.lastIndexOf('.');
            if (dot < 0 || dot < slash) return false;
            var base = relativePath.substring(slash + 1, dot);
            return classSuffixes.stream().anyMatch(base::endsWith);
        }
    }

    private record ConfigMatcher() implements SelectorMatcher {
        @Override
        public boolean matches(String relativePath) {
            if (relativePath.contains("/config/")) return true;
            int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
            int dot = relativePath.lastIndexOf('.');
            if (dot < 0 || dot < slash) return false;
            var base = relativePath.substring(slash + 1, dot);
            return base.endsWith("Config") || base.endsWith("Configuration");
        }
    }

    private record GlobMatcher(List<String> globs) implements SelectorMatcher {
        @Override
        public boolean matches(String relativePath) {
            if (globs == null || globs.isEmpty()) return false;
            for (var glob : globs) {
                PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                if (pm.matches(Path.of(relativePath))) return true;
            }
            return false;
        }
    }
}
