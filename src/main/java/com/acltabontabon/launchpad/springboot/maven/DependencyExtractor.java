package com.acltabontabon.launchpad.springboot.maven;

import com.acltabontabon.launchpad.scanner.Dependency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts declared dependencies from the project's {@code pom.xml}. Delegates
 * the actual XML parse to {@link MavenModelParser} - the same structured Maven
 * entry point used by {@link ProjectSupportDetector} - so dependency
 * extraction and support detection never diverge in their reading of the pom.
 *
 * <p>Output is deduped by {@code name@scope} while preserving declaration order.
 */
public final class DependencyExtractor {

    private final MavenModelParser mavenModelParser;

    public DependencyExtractor() {
        this(new MavenModelParser());
    }

    public DependencyExtractor(MavenModelParser mavenModelParser) {
        this.mavenModelParser = mavenModelParser;
    }

    public List<Dependency> extract(Map<String, String> keyFileContents) {
        var deps = new LinkedHashMap<String, Dependency>();
        var pom = keyFileContents.get("pom.xml");
        if (pom == null || pom.isBlank()) return new ArrayList<>();
        var model = mavenModelParser.parse(pom);
        for (var dep : model.dependencies()) {
            deps.putIfAbsent(key(dep), dep);
        }
        return new ArrayList<>(deps.values());
    }

    private static String key(Dependency d) {
        return d.name() + "@" + (d.scope() == null ? "" : d.scope());
    }
}
