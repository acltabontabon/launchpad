package com.acltabontabon.launchpad.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.model.VirtualProjectContextStore;
import com.acltabontabon.launchpad.model.graph.EdgeType;
import com.acltabontabon.launchpad.model.graph.NodeType;
import com.acltabontabon.launchpad.model.graph.ProjectEdge;
import com.acltabontabon.launchpad.model.graph.ProjectModel;
import com.acltabontabon.launchpad.model.graph.ProjectModelStore;
import com.acltabontabon.launchpad.model.graph.ProjectNode;
import com.acltabontabon.launchpad.model.graph.SourceRef;
import com.acltabontabon.launchpad.standards.RemoteStandardsFetcher;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused coverage for the deterministic-graph MCP tools (get_repo_map,
 * get_architecture, get_task_context). A small {@link ProjectModel} is written
 * to {@code .launchpad/project.model.json} so the tools exercise the same read
 * path an MCP client hits at runtime. Runs fully offline.
 */
class LaunchpadMcpToolsGraphTest {

    private final ProjectModelStore graphStore = new ProjectModelStore();

    /** Build a tool instance with the given response-mode property (null = references default). */
    private LaunchpadMcpTools toolsWith(String responseModeProperty) {
        var settings = new LaunchpadSettings("auto", "http://localhost:11434",
            "qwen2.5-coder:7b-instruct", "", event -> { });
        var standardsLoader = new StandardsLoader(new RemoteStandardsFetcher(settings), settings);
        return new LaunchpadMcpTools(
            null, null, null, standardsLoader,
            new ProjectRegistry(),
            null,
            new VirtualProjectContextStore(),
            graphStore,
            null,
            512L,
            responseModeProperty);
    }

    /**
     * A small but representative graph: a project that contains two packages,
     * one of which contains a controller component that exposes an endpoint, and
     * the project depends on one external dependency.
     */
    private void writeGraph(Path projectRoot) {
        var nodes = List.of(
            new ProjectNode("project/demo", NodeType.PROJECT, "demo", null, Map.of(), "h0"),
            new ProjectNode("package/orders", NodeType.PACKAGE, "com.example.orders",
                SourceRef.of("src/main/java/com/example/orders"), Map.of(), "h1"),
            new ProjectNode("package/users", NodeType.PACKAGE, "com.example.users",
                SourceRef.of("src/main/java/com/example/users"), Map.of(), "h2"),
            new ProjectNode("component/ordercontroller", NodeType.COMPONENT, "OrderController",
                SourceRef.of("src/main/java/com/example/orders/OrderController.java", 10, 40),
                Map.of("kind", "controller"), "h3"),
            new ProjectNode("endpoint/get-orders", NodeType.ENDPOINT, "GET /orders",
                null, Map.of("method", "GET"), "h4"),
            new ProjectNode("dependency/spring-web", NodeType.DEPENDENCY, "spring-web",
                null, Map.of("version", "6.1.0"), "h5"));
        var edges = List.of(
            new ProjectEdge("project/demo", "package/orders", EdgeType.CONTAINS),
            new ProjectEdge("project/demo", "package/users", EdgeType.CONTAINS),
            new ProjectEdge("package/orders", "component/ordercontroller", EdgeType.CONTAINS),
            new ProjectEdge("component/ordercontroller", "endpoint/get-orders", EdgeType.EXPOSES),
            new ProjectEdge("project/demo", "dependency/spring-web", EdgeType.DEPENDS_ON));
        graphStore.save(projectRoot,
            new ProjectModel(ProjectModel.SCHEMA_VERSION, "2026-06-25T00:00:00Z", "demo",
                projectRoot.toString(), nodes, edges));
    }

    @Test
    @SuppressWarnings("unchecked")
    void repoMapReturnsOnlyContainmentNodesAndEdges(@TempDir Path tmp) {
        var projectRoot = tmp.resolve("proj");
        writeGraph(projectRoot);

        var out = toolsWith(null).getRepoMap(projectRoot.toString());
        assertThat(out).containsEntry("responseMode", "references");
        assertThat(out).containsEntry("model", ".launchpad/project.model.json");

        var nodes = (List<Map<String, Object>>) out.get("nodes");
        assertThat(nodes).extracting(n -> n.get("type"))
            .containsOnly("project", "package");
        assertThat(nodes).hasSize(3);

        var edges = (List<Map<String, Object>>) out.get("edges");
        assertThat(edges).extracting(e -> e.get("type")).containsOnly("contains");

        // Source refs are projected when present.
        var pkg = nodes.stream().filter(n -> "package/orders".equals(n.get("id"))).findFirst().get();
        var source = (Map<String, Object>) pkg.get("source");
        assertThat(source).containsEntry("path", "src/main/java/com/example/orders");
    }

    @Test
    @SuppressWarnings("unchecked")
    void architectureReturnsComponentEndpointAndDependencyWiring(@TempDir Path tmp) {
        var projectRoot = tmp.resolve("proj");
        writeGraph(projectRoot);

        var out = toolsWith(null).getArchitecture(projectRoot.toString());
        var nodes = (List<Map<String, Object>>) out.get("nodes");
        assertThat(nodes).extracting(n -> n.get("type"))
            .containsExactlyInAnyOrder("component", "endpoint", "dependency");

        var edges = (List<Map<String, Object>>) out.get("edges");
        assertThat(edges).extracting(e -> e.get("type"))
            .containsExactlyInAnyOrder("exposes", "depends-on");
    }

    @Test
    @SuppressWarnings("unchecked")
    void taskContextSlicesMatchedNodePlusOneHopNeighbors(@TempDir Path tmp) {
        var projectRoot = tmp.resolve("proj");
        writeGraph(projectRoot);

        var out = toolsWith(null).getTaskContext(projectRoot.toString(), "ordercontroller");
        assertThat(out).containsEntry("matchedCount", 1);
        assertThat(out).containsEntry("truncated", false);

        var nodes = (List<Map<String, Object>>) out.get("nodes");
        var byId = nodes.stream().collect(
            java.util.stream.Collectors.toMap(n -> (String) n.get("id"), n -> n));
        // The matched component is flagged; its container and exposed endpoint
        // come along as one-hop neighbors that are not flagged.
        assertThat(byId.get("component/ordercontroller")).containsEntry("matched", true);
        assertThat(byId.get("package/orders")).containsEntry("matched", false);
        assertThat(byId.get("endpoint/get-orders")).containsEntry("matched", false);

        var edges = (List<Map<String, Object>>) out.get("edges");
        assertThat(edges).extracting(e -> e.get("type"))
            .containsExactlyInAnyOrder("contains", "exposes");
    }

    @Test
    void taskContextRejectsBlankQuery(@TempDir Path tmp) {
        var projectRoot = tmp.resolve("proj");
        writeGraph(projectRoot);

        var out = toolsWith(null).getTaskContext(projectRoot.toString(), "  ");
        @SuppressWarnings("unchecked")
        var err = (Map<String, Object>) out.get("error");
        assertThat(err).containsEntry("code", "missing_argument");
        @SuppressWarnings("unchecked")
        var det = (Map<String, Object>) err.get("details");
        assertThat(det).containsEntry("field", "query");
    }

    @Test
    void graphToolsReportNoProjectGraphBeforeScan(@TempDir Path tmp) {
        var projectRoot = tmp.resolve("proj");
        var tools = toolsWith(null);
        for (var out : List.of(
            tools.getRepoMap(projectRoot.toString()),
            tools.getArchitecture(projectRoot.toString()),
            tools.getTaskContext(projectRoot.toString(), "anything"))) {
            assertThat((Map<String, Object>) out.get("error"))
                .containsEntry("code", "no_project_graph");
        }
    }

    @Test
    void inlineModeOmitsTheModelPointer(@TempDir Path tmp) {
        var projectRoot = tmp.resolve("proj");
        writeGraph(projectRoot);

        var out = toolsWith("inline").getRepoMap(projectRoot.toString());
        assertThat(out).doesNotContainKeys("responseMode", "model");
        assertThat(out).containsKeys("nodes", "edges");
    }
}
