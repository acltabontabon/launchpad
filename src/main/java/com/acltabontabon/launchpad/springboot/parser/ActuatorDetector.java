package com.acltabontabon.launchpad.springboot.parser;

import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Detects Spring Boot Actuator endpoints exposed by the scanned project so
 * the `## Endpoints` table can list them alongside `@RestController` routes.
 * <p>
 * Triggers only when the project depends on {@code spring-boot-starter-actuator}.
 * Reads the project's {@code application.properties} / {@code application.yml}
 * to find {@code management.endpoints.web.exposure.include} and
 * {@code management.endpoints.web.base-path}. Defaults match Spring Boot 2.x+
 * (only {@code health} is exposed; base path is {@code /actuator}).
 * <p>
 * Returns the well-known descriptive notes alongside each endpoint so the
 * caller has a deterministic fallback when LLM-driven Notes synthesis
 * doesn't cover one.
 */
public final class ActuatorDetector {

    private static final String ACTUATOR_ARTIFACT = "spring-boot-starter-actuator";
    private static final String DEFAULT_BASE_PATH = "/actuator";

    /** Endpoint name -> short generic description used when the LLM has nothing better. */
    private static final Map<String, String> WELL_KNOWN = new LinkedHashMap<>() {{
        put("health", "");
        put("info", "Application metadata");
        put("metrics", "Application metrics");
        put("prometheus", "Prometheus scrape endpoint");
        put("env", "Environment properties");
        put("beans", "Active Spring beans");
        put("mappings", "All HTTP routes");
        put("loggers", "Runtime logger config");
        put("threaddump", "Thread dump");
        put("heapdump", "Heap dump");
        put("configprops", "@ConfigurationProperties values");
        put("conditions", "Auto-configuration conditions");
        put("caches", "Cache statistics");
        put("httptrace", "Recent HTTP requests");
        put("auditevents", "Security audit log");
        put("scheduledtasks", "Scheduled task list");
        put("shutdown", "Graceful shutdown trigger");
    }};

    private static final Pattern PROP_LINE = Pattern.compile(
        "^\\s*([\\w.-]+)\\s*=\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern YAML_KV = Pattern.compile(
        "^([\\s]*)([\\w.-]+)\\s*:\\s*(.*)$", Pattern.MULTILINE);
    private static final Pattern BUILD_INFO_GOAL = Pattern.compile(
        "<goal>\\s*build-info\\s*</goal>", Pattern.CASE_INSENSITIVE);

    private ActuatorDetector() {}

    /** Result wrapper: detected endpoints + deterministic notes-by-key fallback. */
    public record Detection(List<Endpoint> endpoints, Map<String, String> notes) {
        public static Detection empty() {
            return new Detection(List.of(), Map.of());
        }
    }

    public static Detection detect(List<Dependency> deps,
                                   Map<String, String> appConfigByPath,
                                   String pomXmlContent) {
        if (!hasActuator(deps)) return Detection.empty();

        String basePath = DEFAULT_BASE_PATH;
        var exposed = new LinkedHashSet<String>();
        // Spring Boot default exposure (web): only `health`.
        exposed.add("health");

        if (appConfigByPath != null) {
            for (var entry : appConfigByPath.entrySet()) {
                var path = entry.getKey().toLowerCase(Locale.ROOT);
                var content = entry.getValue();
                if (content == null || content.isBlank()) continue;
                if (path.endsWith("application.properties")) {
                    applyProperties(content, exposed, ref -> {
                        if (ref.basePath != null) return ref.basePath;
                        return null;
                    });
                    var override = readPropertiesBasePath(content);
                    if (override != null) basePath = override;
                } else if (path.endsWith("application.yml") || path.endsWith("application.yaml")) {
                    applyYaml(content, exposed);
                    var override = readYamlBasePath(content);
                    if (override != null) basePath = override;
                }
            }
        }

        boolean hasBuildInfo = pomXmlContent != null && BUILD_INFO_GOAL.matcher(pomXmlContent).find();

        var endpoints = new ArrayList<Endpoint>();
        var notes = new LinkedHashMap<String, String>();
        for (var name : exposed) {
            var endpointPath = joinPath(basePath, name);
            endpoints.add(new Endpoint("GET", endpointPath, "actuator"));
            notes.put("GET " + endpointPath, noteFor(name, hasBuildInfo));
        }
        return new Detection(List.copyOf(endpoints), Map.copyOf(notes));
    }

    private static boolean hasActuator(List<Dependency> deps) {
        if (deps == null) return false;
        for (var d : deps) {
            if (d.name() != null && d.name().contains(ACTUATOR_ARTIFACT)) return true;
        }
        return false;
    }

    /** Helper to thread state through the simpler `applyProperties` path. */
    private record PropRef(String basePath) {}

    /**
     * Apply `management.endpoints.web.exposure.include` from a .properties file.
     * Adds resolved endpoint names to {@code exposed}.
     */
    private static void applyProperties(String content, LinkedHashSet<String> exposed,
                                        java.util.function.Function<PropRef, String> ignored) {
        var m = PROP_LINE.matcher(content);
        while (m.find()) {
            var key = m.group(1).strip();
            var value = m.group(2).strip();
            if (key.equalsIgnoreCase("management.endpoints.web.exposure.include")) {
                addExposure(exposed, value);
            }
        }
    }

    private static String readPropertiesBasePath(String content) {
        var m = PROP_LINE.matcher(content);
        while (m.find()) {
            if (m.group(1).strip().equalsIgnoreCase("management.endpoints.web.base-path")) {
                return normalizeBasePath(m.group(2).strip());
            }
        }
        return null;
    }

    /**
     * Apply `management.endpoints.web.exposure.include` from a YAML file. This
     * is intentionally indent-naive: we look for the `include:` line anywhere
     * under a `management:` ancestor. Good enough for the simple configs the
     * vast majority of Spring projects use.
     */
    private static void applyYaml(String content, LinkedHashSet<String> exposed) {
        var m = YAML_KV.matcher(content);
        while (m.find()) {
            var key = m.group(2).strip();
            var value = m.group(3).strip();
            if (key.equalsIgnoreCase("include") && !value.isEmpty()) {
                // Heuristic: only honor when nearby content mentions exposure.
                int matchStart = Math.max(0, m.start() - 160);
                var context = content.substring(matchStart, m.start()).toLowerCase(Locale.ROOT);
                if (context.contains("exposure")) addExposure(exposed, value);
            }
        }
    }

    private static String readYamlBasePath(String content) {
        var m = YAML_KV.matcher(content);
        while (m.find()) {
            var key = m.group(2).strip();
            var value = m.group(3).strip();
            if (key.equalsIgnoreCase("base-path") && !value.isEmpty()) {
                int matchStart = Math.max(0, m.start() - 160);
                var context = content.substring(matchStart, m.start()).toLowerCase(Locale.ROOT);
                if (context.contains("management") || context.contains("endpoints")) {
                    return normalizeBasePath(stripYamlValue(value));
                }
            }
        }
        return null;
    }

    private static void addExposure(LinkedHashSet<String> exposed, String value) {
        var cleaned = stripYamlValue(value);
        if (cleaned.equals("*")) {
            exposed.addAll(WELL_KNOWN.keySet());
            return;
        }
        for (var raw : cleaned.split(",")) {
            var name = raw.strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (WELL_KNOWN.containsKey(name)) exposed.add(name);
        }
    }

    /** Strip YAML quotes / brackets that callers commonly put around list values. */
    private static String stripYamlValue(String raw) {
        var v = raw.strip();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1);
        }
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String normalizeBasePath(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_BASE_PATH;
        var p = raw.strip();
        if ((p.startsWith("\"") && p.endsWith("\"")) || (p.startsWith("'") && p.endsWith("'"))) {
            p = p.substring(1, p.length() - 1);
        }
        if (!p.startsWith("/")) p = "/" + p;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String joinPath(String basePath, String endpointName) {
        var base = basePath == null || basePath.isBlank() ? DEFAULT_BASE_PATH : basePath;
        return base + "/" + endpointName;
    }

    private static String noteFor(String name, boolean hasBuildInfo) {
        if (name.equals("info") && hasBuildInfo) {
            return "Returns build metadata from `build-info.properties`";
        }
        return WELL_KNOWN.getOrDefault(name, "");
    }
}
