package com.acltabontabon.launchpad.model.graph;

import com.acltabontabon.launchpad.model.ModelIdentity;
import com.acltabontabon.launchpad.scanner.ClassFact;
import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.scanner.PackageSummary;
import com.acltabontabon.launchpad.scanner.ProjectClassFacts;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Builds a deterministic {@link ProjectModel} graph from the structural facts
 * of a {@link ProjectContext} scan. Mirrors
 * {@link com.acltabontabon.launchpad.model.ProjectContextAssembler}: everything
 * here is derived from the deterministic scan with no model call, so the
 * output is reproducible across re-runs.
 * <p>
 * The {@code @Component} instance is stateless; each call drives a short-lived
 * {@link Assembly} that accumulates nodes, edges, and the id lookups the edge
 * steps need.
 */
@Component
public class ProjectModelAssembler {

    /**
     * Assemble the project-model graph. {@code generatedAt} is an ISO-8601
     * timestamp recorded on the model but never folded into any node content
     * hash, so two runs differing only by timestamp produce identical nodes
     * and edges.
     */
    public ProjectModel assemble(ProjectContext scan, String generatedAt) {
        if (scan == null) {
            return new ProjectModel(ProjectModel.SCHEMA_VERSION, generatedAt, "", "", List.of(), List.of());
        }

        var facts = scan.rootPath() == null ? List.<ClassFact>of()
            : ProjectClassFacts.collect(Path.of(scan.rootPath()), scan.sourceFiles(), scan.endpoints());

        var a = new Assembly();
        addProject(a, scan);
        addPackages(a, scan);
        addComponents(a, facts);
        addEndpoints(a, scan, facts);
        addEntryPoints(a, scan, facts);
        addDependencies(a, scan);
        linkImplements(a, facts);

        return new ProjectModel(ProjectModel.SCHEMA_VERSION, generatedAt,
            nullToEmpty(scan.name()), nullToEmpty(scan.rootPath()), a.nodes, a.edges);
    }

    /** The single root node; everything else hangs off it. */
    private void addProject(Assembly a, ProjectContext scan) {
        a.projectId = a.add(NodeType.PROJECT, slug(scan.name()), nullToEmpty(scan.name()),
            SourceRef.of(scan.rootPath()),
            Map.of("stack", scan.stack() != null ? scan.stack().displayName() : ""));
    }

    /** One PACKAGE per inferred source directory; the project contains each. */
    private void addPackages(Assembly a, ProjectContext scan) {
        for (PackageSummary pkg : safe(scan.packageSummaries())) {
            String id = a.add(NodeType.PACKAGE, slug(pkg.path()), pkg.path(),
                SourceRef.of(pkg.path()), Map.of("fileCount", Integer.toString(pkg.fileCount())));
            a.packageIdByPath.put(pkg.path(), id);
            a.edge(a.projectId, id, EdgeType.CONTAINS);
        }
    }

    /** One COMPONENT per classified source file; each is contained by its most-specific package. */
    private void addComponents(Assembly a, List<ClassFact> facts) {
        // Longest path first so a component attaches to its deepest enclosing package.
        var packagesByDepth = new ArrayList<>(a.packageIdByPath.keySet());
        packagesByDepth.sort(Comparator.comparingInt(String::length).reversed());

        for (ClassFact fact : facts) {
            String id = a.add(NodeType.COMPONENT, slug(fact.relativePath()), fact.name(),
                SourceRef.of(fact.relativePath(), fact.startLine(), fact.endLine()),
                Map.of("kind", fact.kind().name().toLowerCase(Locale.ROOT),
                    "leafPackage", nullToEmpty(fact.leafPackage())));
            a.componentIdByName.putIfAbsent(fact.name(), id);
            a.componentIdByPath.put(fact.relativePath(), id);

            for (String pkgPath : packagesByDepth) {
                if (fact.relativePath().startsWith(pkgPath + "/")) {
                    a.edge(a.packageIdByPath.get(pkgPath), id, EdgeType.CONTAINS);
                    break;
                }
            }
        }
    }

    /** One ENDPOINT per detected route; its controller component exposes it. */
    private void addEndpoints(Assembly a, ProjectContext scan, List<ClassFact> facts) {
        var factByName = indexByName(facts);
        for (Endpoint ep : safe(scan.endpoints())) {
            String controllerClass = handlerClass(ep.handler());
            ClassFact controller = controllerClass == null ? null : factByName.get(controllerClass);
            SourceRef source = controller == null ? null
                : SourceRef.of(controller.relativePath(), ep.line(), ep.line());
            String id = a.add(NodeType.ENDPOINT, slug(ep.method() + " " + ep.path()),
                ep.method() + " " + ep.path(), source,
                Map.of("method", nullToEmpty(ep.method()), "httpPath", nullToEmpty(ep.path()),
                    "handler", nullToEmpty(ep.handler())));

            String componentId = controllerClass == null ? null : a.componentIdByName.get(controllerClass);
            if (componentId != null) {
                a.edge(componentId, id, EdgeType.EXPOSES);
            }
        }
    }

    /** One ENTRYPOINT per detected entry; reuses the matching component's line range when known. */
    private void addEntryPoints(Assembly a, ProjectContext scan, List<ClassFact> facts) {
        for (Map.Entry<String, String> entry : safe(scan.entryPoints()).entrySet()) {
            String path = entry.getValue();
            ClassFact match = factByPath(facts, path);
            SourceRef source = match != null
                ? SourceRef.of(path, match.startLine(), match.endLine())
                : SourceRef.of(path);
            a.add(NodeType.ENTRYPOINT, slug(entry.getKey()), entry.getKey(), source,
                Map.of("target", nullToEmpty(path)));
        }
    }

    /** One DEPENDENCY per declared external dependency; the project depends on each (MVP: no package fan-out). */
    private void addDependencies(Assembly a, ProjectContext scan) {
        String manifest = manifestFor(scan);
        for (Dependency dep : safe(scan.dependencies())) {
            String id = a.add(NodeType.DEPENDENCY, slug(dep.name()), dep.name(),
                manifest == null ? null : SourceRef.of(manifest),
                Map.of("version", nullToEmpty(dep.version()), "scope", nullToEmpty(dep.scope())));
            a.edge(a.projectId, id, EdgeType.DEPENDS_ON);
        }
    }

    /** Wire implementation components to the interfaces they implement (needs all components present). */
    private void linkImplements(Assembly a, List<ClassFact> facts) {
        for (ClassFact fact : facts) {
            if (fact.kind() != ClassFact.Kind.INTERFACE || fact.impls().isEmpty()) continue;
            String ifaceId = a.componentIdByPath.get(fact.relativePath());
            for (String implName : fact.impls()) {
                String implId = a.componentIdByName.get(implName);
                if (implId != null) {
                    a.edge(implId, ifaceId, EdgeType.IMPLEMENTS);
                }
            }
        }
    }

    /** Per-assembly mutable working set: the graph plus the id lookups edges resolve against. */
    private static final class Assembly {
        final List<ProjectNode> nodes = new ArrayList<>();
        final List<ProjectEdge> edges = new ArrayList<>();
        private final Set<String> usedIds = new HashSet<>();

        String projectId;
        final Map<String, String> packageIdByPath = new LinkedHashMap<>();
        final Map<String, String> componentIdByName = new LinkedHashMap<>(); // first wins
        final Map<String, String> componentIdByPath = new LinkedHashMap<>();

        /** Adds a node with a deterministically de-duplicated id; returns the final id. */
        String add(NodeType type, String slug, String name, SourceRef source, Map<String, String> attributes) {
            String base = type.json() + "/" + (slug == null || slug.isBlank() ? "unnamed" : slug);
            String id = base;
            int n = 2;
            while (!usedIds.add(id)) {
                id = base + "-" + n++;
            }
            nodes.add(new ProjectNode(id, type, name, source, attributes,
                contentHash(type, name, source, attributes)));
            return id;
        }

        void edge(String from, String to, EdgeType type) {
            edges.add(new ProjectEdge(from, to, type));
        }
    }

    /**
     * Stable digest over a node's content (type, name, source, attributes),
     * walked in a fixed order. Excludes the id and the generation timestamp so
     * the hash tracks meaningful drift only.
     */
    private static String contentHash(NodeType type, String name, SourceRef source, Map<String, String> attributes) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(type.json()).append('\n');
        canonical.append(nullToEmpty(name)).append('\n');
        if (source != null) {
            canonical.append(nullToEmpty(source.path())).append(':')
                .append(source.startLine()).append('-').append(source.endLine());
        }
        canonical.append('\n');
        new TreeMap<>(attributes == null ? Map.of() : attributes)
            .forEach((k, v) -> canonical.append(k).append('=').append(nullToEmpty(v)).append('\n'));
        return ModelIdentity.sha256(canonical.toString());
    }

    /** "ClassName" from a "ClassName.method" handler reference, or null. */
    private static String handlerClass(String handler) {
        if (handler == null || handler.isBlank()) return null;
        int dot = handler.indexOf('.');
        String clazz = dot > 0 ? handler.substring(0, dot) : handler;
        return clazz.isBlank() ? null : clazz;
    }

    /** Index facts by simple class name, first occurrence winning (mirrors the component-id index). */
    private static Map<String, ClassFact> indexByName(List<ClassFact> facts) {
        var byName = new LinkedHashMap<String, ClassFact>();
        for (ClassFact f : facts) {
            byName.putIfAbsent(f.name(), f);
        }
        return byName;
    }

    private static ClassFact factByPath(List<ClassFact> facts, String relativePath) {
        if (relativePath == null) return null;
        for (ClassFact f : facts) {
            if (relativePath.equals(f.relativePath())) return f;
        }
        return null;
    }

    /** The build manifest a dependency is declared in, per the detected build tool. */
    private static String manifestFor(ProjectContext scan) {
        String tool = scan.stack() != null ? scan.stack().buildTool() : null;
        if (tool == null) return null;
        return "gradle".equalsIgnoreCase(tool) ? "build.gradle" : "pom.xml";
    }

    private static String slug(String value) {
        return ModelIdentity.slug(value);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static <K, V> Map<K, V> safe(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }
}
