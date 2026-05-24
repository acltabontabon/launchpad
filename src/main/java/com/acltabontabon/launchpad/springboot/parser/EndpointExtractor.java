package com.acltabontabon.launchpad.springboot.parser;

import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap regex pass over Spring controller sources to extract HTTP routes.
 * Drives the `## Endpoints` table in the generated CLAUDE.md.
 * <p>
 * Misses dynamic registration (functional WebFlux routes, controller-advice
 * catch-alls). That's acceptable: the goal is to enrich the prompt with
 * concrete paths the model can paraphrase, not to be exhaustive.
 */
public final class EndpointExtractor {

    private static final Pattern CLASS_MAPPING = Pattern.compile(
        "@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    // Captures: ($1) verb word; ($2) optional full args block (nullable).
    // Parens are optional so bare annotations like `@GetMapping` still match.
    private static final Pattern METHOD_MAPPING = Pattern.compile(
        "@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern FIRST_STRING_LITERAL = Pattern.compile(
        "(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern METHOD_NAME = Pattern.compile(
        "(?:public|protected|private)?\\s+[\\w<>,\\s\\?\\[\\]]+\\s+(\\w+)\\s*\\(");
    private static final Pattern CLASS_NAME = Pattern.compile(
        "(?:public\\s+)?(?:final\\s+)?class\\s+(\\w+)");

    /**
     * Source snippets (path → first {@link #SOURCE_SNIPPET_MAX_LINES} lines)
     * captured during {@link #extract(Path, List)}. Populated as a side
     * effect so the api-surface synthesis job can feed actual controller
     * bodies into its evidence packet without re-reading the same files.
     */
    private static final int SOURCE_SNIPPET_MAX_LINES = 120;
    private final java.util.LinkedHashMap<String, String> controllerSources = new java.util.LinkedHashMap<>();

    public List<Endpoint> extract(Path root, List<String> sourceFiles) {
        var endpoints = new ArrayList<Endpoint>();
        controllerSources.clear();
        for (var relative : sourceFiles) {
            if (!looksLikeController(relative)) continue;
            try {
                var path = root.resolve(relative);
                if (!Files.isRegularFile(path)) continue;
                var content = Files.readString(path);
                var parsed = parseControllerFile(content);
                if (parsed.isEmpty()) continue;
                endpoints.addAll(parsed);
                controllerSources.put(relative, firstLines(content, SOURCE_SNIPPET_MAX_LINES));
            } catch (Exception ignored) {
                // unreadable file: skip silently, endpoints are best-effort
            }
        }
        return endpoints;
    }

    /**
     * Returns the controller source snippets gathered during the last
     * {@link #extract(Path, List)} call. Keyed by relative path; values are
     * the first {@link #SOURCE_SNIPPET_MAX_LINES} lines.
     */
    public java.util.Map<String, String> getControllerSources() {
        return java.util.Map.copyOf(controllerSources);
    }

    private static String firstLines(String content, int maxLines) {
        var lines = content.split("\n", -1);
        if (lines.length <= maxLines) return content;
        var sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]);
            if (i < maxLines - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean looksLikeController(String relativePath) {
        if (!(relativePath.endsWith(".java") || relativePath.endsWith(".kt"))) return false;
        var lower = relativePath.toLowerCase(Locale.ROOT);
        return lower.contains("controller") || lower.contains("/handler/") || lower.contains("/api/");
    }

    static List<Endpoint> parseControllerFile(String content) {
        if (content == null || content.isBlank()) return List.of();
        if (!content.contains("Mapping(") && !content.contains("Mapping ")) return List.of();

        var className = firstMatch(CLASS_NAME, content, 1);

        // Find class-level @RequestMapping, if any. Remember the match offset
        // so we can exclude it from the method-mapping iteration (the verb
        // alternation includes "Request", which would otherwise double-count
        // the class-level mapping as a method-level GET-equivalent).
        String basePath = "";
        int classMappingOffset = -1;
        var classMatcher = CLASS_MAPPING.matcher(content);
        if (classMatcher.find()) {
            basePath = normalizePath(classMatcher.group(1));
            classMappingOffset = classMatcher.start();
        }

        var out = new ArrayList<Endpoint>();
        var matcher = METHOD_MAPPING.matcher(content);
        while (matcher.find()) {
            if (matcher.start() == classMappingOffset) continue;
            var verb = matcher.group(1).toUpperCase(Locale.ROOT);
            var argsBlock = matcher.group(2);
            var pathToken = extractFirstStringLiteral(argsBlock);
            var methodPath = normalizePath(pathToken);
            var fullPath = joinPaths(basePath, methodPath);

            var handlerName = findHandlerNameAfter(content, matcher.end());
            var handler = className != null
                ? className + (handlerName != null ? "." + handlerName : "")
                : (handlerName != null ? handlerName : "");

            out.add(new Endpoint(verb, fullPath, handler));
        }
        return out;
    }

    private static String firstMatch(Pattern p, String content, int group) {
        Matcher m = p.matcher(content);
        return m.find() ? m.group(group) : null;
    }

    private static String extractFirstStringLiteral(String argsBlock) {
        if (argsBlock == null || argsBlock.isEmpty()) return null;
        var m = FIRST_STRING_LITERAL.matcher(argsBlock);
        return m.find() ? m.group(1) : null;
    }

    private static String findHandlerNameAfter(String content, int from) {
        // Scan forward to the first method signature; skip param annotations,
        // other annotations stacked on the method, generics, etc. Best-effort.
        int searchEnd = Math.min(content.length(), from + 400);
        var matcher = METHOD_NAME.matcher(content.substring(from, searchEnd));
        while (matcher.find()) {
            var name = matcher.group(1);
            if (!isReservedKeyword(name)) return name;
        }
        return null;
    }

    private static boolean isReservedKeyword(String name) {
        return switch (name) {
            case "if", "for", "while", "switch", "return", "new", "this", "super",
                 "class", "interface", "enum", "record", "void" -> true;
            default -> false;
        };
    }

    private static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) return "";
        var trimmed = raw.strip();
        if (!trimmed.startsWith("/")) trimmed = "/" + trimmed;
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String joinPaths(String base, String method) {
        if (base.isEmpty() && method.isEmpty()) return "/";
        if (base.isEmpty()) return method.isEmpty() ? "/" : method;
        if (method.isEmpty()) return base;
        return base + method;
    }
}
