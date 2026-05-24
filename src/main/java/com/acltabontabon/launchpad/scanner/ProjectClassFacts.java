package com.acltabontabon.launchpad.scanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a list of {@link ClassFact} for every classifiable JVM source file
 * in the project, then enriches:
 * <ul>
 *   <li>interfaces with the names of classes that {@code implements} them
 *       anywhere in the source tree;</li>
 *   <li>controllers with the HTTP routes detected by {@link EndpointExtractor}.</li>
 * </ul>
 * Caps the total number of classified files at {@link #MAX_FILES} so a huge
 * monorepo cannot blow the scan budget.
 */
public final class ProjectClassFacts {

    /** Hard cap on how many sources we classify - keeps scan latency bounded. */
    private static final int MAX_FILES = 150;
    /** Hard cap on how many sources we re-read for impl detection. */
    private static final int MAX_IMPL_SCAN_FILES = 300;
    private static final Pattern IMPLEMENTS_CLAUSE = Pattern.compile(
        "\\b(?:public\\s+|abstract\\s+|final\\s+)?class\\s+(\\w+)[^{]*\\bimplements\\s+([\\w,\\s]+)",
        Pattern.MULTILINE);

    private ProjectClassFacts() {}

    /**
     * Collects classified facts for every JVM source file the scanner found.
     * Empty when the project has no Java / Kotlin sources.
     */
    public static List<ClassFact> collect(Path projectRoot,
                                          List<String> sourceFiles,
                                          List<Endpoint> endpoints) {
        if (projectRoot == null || sourceFiles == null || sourceFiles.isEmpty()) return List.of();

        var facts = new ArrayList<ClassFact>();
        int classified = 0;
        for (var rel : sourceFiles) {
            if (classified >= MAX_FILES) break;
            var f = ClassClassifier.classify(projectRoot, rel);
            if (f == null) continue;
            facts.add(f);
            classified++;
        }
        if (facts.isEmpty()) return facts;

        var withImpls = enrichInterfaces(facts, projectRoot, sourceFiles);
        return enrichControllers(withImpls, endpoints);
    }

    /** Group facts by leaf package, preserving the order they were collected. */
    public static Map<String, List<ClassFact>> groupByLeafPackage(List<ClassFact> facts) {
        var out = new LinkedHashMap<String, List<ClassFact>>();
        for (var f : facts) {
            out.computeIfAbsent(f.leafPackage(), k -> new ArrayList<>()).add(f);
        }
        return out;
    }

    /** Second pass: scan source bodies for `implements <Name>` and attach impl class lists. */
    private static List<ClassFact> enrichInterfaces(List<ClassFact> facts, Path root, List<String> sourceFiles) {
        var interfaceNames = facts.stream()
            .filter(f -> f.kind() == ClassFact.Kind.INTERFACE)
            .map(ClassFact::name)
            .collect(java.util.stream.Collectors.toSet());
        if (interfaceNames.isEmpty()) return facts;

        var implMap = new LinkedHashMap<String, LinkedHashSet<String>>();
        int scanned = 0;
        for (var rel : sourceFiles) {
            if (scanned >= MAX_IMPL_SCAN_FILES) break;
            if (!ClassClassifier.isJvmSource(rel)) continue;
            String content;
            try {
                var path = root.resolve(rel);
                if (!Files.isRegularFile(path)) continue;
                if (Files.size(path) > 24 * 1024) continue;
                content = Files.readString(path);
            } catch (Exception e) {
                continue;
            }
            scanned++;
            Matcher m = IMPLEMENTS_CLAUSE.matcher(content);
            while (m.find()) {
                var implName = m.group(1);
                for (var iface : m.group(2).split(",")) {
                    var trimmed = iface.strip().replaceAll("<.*>", "");
                    if (interfaceNames.contains(trimmed)) {
                        implMap.computeIfAbsent(trimmed, k -> new LinkedHashSet<>()).add(implName);
                    }
                }
            }
        }
        if (implMap.isEmpty()) return facts;

        var out = new ArrayList<ClassFact>();
        for (var f : facts) {
            if (f.kind() == ClassFact.Kind.INTERFACE && implMap.containsKey(f.name())) {
                out.add(new ClassFact(f.name(), f.relativePath(), f.leafPackage(), f.kind(),
                    f.routes(), List.copyOf(implMap.get(f.name()))));
            } else {
                out.add(f);
            }
        }
        return out;
    }

    /** Third pass: attach HTTP routes to controllers from the EndpointExtractor output. */
    private static List<ClassFact> enrichControllers(List<ClassFact> facts, List<Endpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) return facts;
        var routesByClass = new LinkedHashMap<String, List<String>>();
        for (var ep : endpoints) {
            var handler = ep.handler();
            var dot = handler.indexOf('.');
            var clazz = dot > 0 ? handler.substring(0, dot) : handler;
            if (clazz.isBlank()) continue;
            routesByClass
                .computeIfAbsent(clazz, k -> new ArrayList<>())
                .add(ep.method() + " " + ep.path());
        }
        if (routesByClass.isEmpty()) return facts;

        var out = new ArrayList<ClassFact>();
        for (var f : facts) {
            if (f.kind() == ClassFact.Kind.REST_CONTROLLER && routesByClass.containsKey(f.name())) {
                out.add(new ClassFact(f.name(), f.relativePath(), f.leafPackage(), f.kind(),
                    List.copyOf(routesByClass.get(f.name())), f.impls()));
            } else {
                out.add(f);
            }
        }
        return out;
    }
}
