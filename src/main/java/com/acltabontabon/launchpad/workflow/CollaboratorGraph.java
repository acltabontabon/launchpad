package com.acltabontabon.launchpad.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic collaborator graph over a project's JVM sources, used to
 * correlate a workflow trigger with the systems, integrations, and data stores
 * it ultimately touches.
 * <p>
 * For each source file it derives the primary class name (from the filename, by
 * Java convention) and the set of <em>collaborator types</em> it references -
 * type tokens whose name ends in a recognized bean suffix
 * ({@code Service}, {@code Repository}, {@code Client}, ...). Edges are
 * class -&gt; referenced collaborator types. {@link #touches(String)} walks the
 * graph transitively from a root class and partitions every reachable
 * collaborator into:
 * <ul>
 *   <li><b>systems</b> - {@code Service}, {@code Manager}, {@code Facade}, {@code UseCase};</li>
 *   <li><b>externalCalls</b> - {@code Client}, {@code Gateway}, {@code Producer},
 *       {@code Publisher}, {@code Sender};</li>
 *   <li><b>dataEffects</b> - {@code Repository}, {@code Dao}, {@code Store}.</li>
 * </ul>
 * This is a heuristic over naming conventions, not a type-resolved call graph;
 * it is fast, reproducible, and never throws. No model is involved.
 */
public final class CollaboratorGraph {

    /** Partitioned collaborators reachable from a root class. Lists are sorted and distinct. */
    public record Collaborators(List<String> systems, List<String> externalCalls, List<String> dataEffects) {
        public static final Collaborators EMPTY = new Collaborators(List.of(), List.of(), List.of());
    }

    private static final int MAX_FILES = 400;
    private static final long MAX_FILE_BYTES = 24 * 1024;
    /** Bound on transitive walk so a dependency cycle or huge graph cannot run away. */
    private static final int MAX_VISITED = 200;

    private static final Pattern COLLABORATOR_TYPE = Pattern.compile(
        "\\b([A-Z]\\w*(?:Service|Manager|Facade|UseCase"
            + "|Repository|Dao|Store"
            + "|Client|Gateway|Producer|Publisher|Sender))\\b");

    /** class simple name -> collaborator type names it references. */
    private final Map<String, Set<String>> edges;

    private CollaboratorGraph(Map<String, Set<String>> edges) {
        this.edges = edges;
    }

    /** Build the graph from the project's source files. Never returns null. */
    public static CollaboratorGraph build(Path projectRoot, List<String> sourceFiles) {
        Map<String, Set<String>> edges = new TreeMap<>();
        if (projectRoot == null || sourceFiles == null) {
            return new CollaboratorGraph(edges);
        }

        int scanned = 0;
        for (String rel : sourceFiles) {
            if (scanned >= MAX_FILES) break;
            if (!isJvmSource(rel)) continue;

            String content = read(projectRoot.resolve(rel));
            if (content == null) continue;
            scanned++;

            String className = classNameOf(rel);
            Set<String> referenced = collaboratorTypes(content, className);
            if (!referenced.isEmpty()) {
                edges.computeIfAbsent(className, key -> new LinkedHashSet<>()).addAll(referenced);
            }
        }
        return new CollaboratorGraph(edges);
    }

    /**
     * Transitively walk from {@code rootClass}, collecting every reachable
     * collaborator type and partitioning it by kind. The root itself is never
     * included. Returns {@link Collaborators#EMPTY} when the root has no edges.
     */
    public Collaborators touches(String rootClass) {
        if (rootClass == null || !edges.containsKey(rootClass)) {
            return Collaborators.EMPTY;
        }

        Set<String> collected = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        var queue = new ArrayDeque<String>();
        queue.add(rootClass);
        visited.add(rootClass);

        while (!queue.isEmpty() && visited.size() <= MAX_VISITED) {
            Set<String> next = edges.getOrDefault(queue.poll(), Set.of());
            for (String collaborator : next) {
                collected.add(collaborator);
                // Recurse only into collaborators that are themselves classes we
                // scanned, so the walk follows real source, not external types.
                if (edges.containsKey(collaborator) && visited.add(collaborator)) {
                    queue.add(collaborator);
                }
            }
        }

        List<String> systems = new ArrayList<>();
        List<String> externalCalls = new ArrayList<>();
        List<String> dataEffects = new ArrayList<>();
        for (String type : collected) {
            switch (kindOf(type)) {
                case SYSTEM -> systems.add(type);
                case EXTERNAL -> externalCalls.add(type);
                case DATA -> dataEffects.add(type);
            }
        }
        return new Collaborators(sorted(systems), sorted(externalCalls), sorted(dataEffects));
    }

    private enum Kind { SYSTEM, EXTERNAL, DATA }

    private static Kind kindOf(String type) {
        if (endsWithAny(type, "Repository", "Dao", "Store")) return Kind.DATA;
        if (endsWithAny(type, "Client", "Gateway", "Producer", "Publisher", "Sender")) return Kind.EXTERNAL;
        return Kind.SYSTEM;
    }

    private static Set<String> collaboratorTypes(String content, String owningClass) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = COLLABORATOR_TYPE.matcher(content);
        while (m.find()) {
            String type = m.group(1);
            if (!type.equals(owningClass)) {
                out.add(type);
            }
        }
        return out;
    }

    /** "com/acme/OrderService.java" -> "OrderService". */
    private static String classNameOf(String relativePath) {
        String name = relativePath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.indexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) return true;
        }
        return false;
    }

    private static List<String> sorted(List<String> values) {
        var distinct = new java.util.TreeSet<>(values);
        return List.copyOf(distinct);
    }

    private static String read(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
                return null;
            }
            return Files.readString(path);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isJvmSource(String relative) {
        return relative.endsWith(".java") || relative.endsWith(".kt");
    }
}
