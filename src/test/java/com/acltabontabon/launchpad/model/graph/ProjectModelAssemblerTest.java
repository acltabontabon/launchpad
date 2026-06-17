package com.acltabontabon.launchpad.model.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.springboot.scanner.ProjectScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Deterministic-generation assertions for the project-model graph sidecar.
 * Runs against the {@code spring-boot} and {@code spring-boot-starter}
 * fixtures and never needs a model, so it is part of {@code ./mvnw test}.
 */
class ProjectModelAssemblerTest {

    private static final String FIXED_TS = "2026-01-01T00:00:00Z";

    private final ProjectScanner scanner = ProjectScanner.forTesting();
    private final ProjectModelAssembler assembler = new ProjectModelAssembler();

    static Stream<String> fixtures() {
        return Stream.of("spring-boot", "spring-boot-starter");
    }

    private ProjectContext scan(String fixture) throws Exception {
        var root = new File("src/test/resources/fixtures/" + fixture).getAbsoluteFile().toPath();
        assertThat(root.toFile().isDirectory()).as("fixture %s exists", fixture).isTrue();
        return scanner.scan(root.toString(), msg -> { });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void nodeIdsAreUnique(String fixture) throws Exception {
        var model = assembler.assemble(scan(fixture), FIXED_TS);

        var ids = model.nodes().stream().map(ProjectNode::id).toList();
        assertThat(ids).as("node ids for %s", fixture).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(id).contains("/"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void outputIsStableAcrossReRuns(String fixture) throws Exception {
        var scan = scan(fixture);

        // Identical timestamp -> identical model, including content hashes.
        assertThat(assembler.assemble(scan, FIXED_TS))
            .as("full model is reproducible for %s", fixture)
            .isEqualTo(assembler.assemble(scan, FIXED_TS));

        // Different timestamp -> identical nodes and edges (timestamp excluded from content).
        var a = assembler.assemble(scan, "2020-01-01T00:00:00Z");
        var b = assembler.assemble(scan, "2099-12-31T23:59:59Z");
        assertThat(a.nodes()).as("nodes ignore generatedAt for %s", fixture).isEqualTo(b.nodes());
        assertThat(a.edges()).as("edges ignore generatedAt for %s", fixture).isEqualTo(b.edges());
    }

    @Test
    void springBootGraphHasExpectedNodeAndEdgeKinds() throws Exception {
        var model = assembler.assemble(scan("spring-boot"), FIXED_TS);

        assertThat(model.schemaVersion()).isEqualTo(ProjectModel.SCHEMA_VERSION);
        assertThat(model.nodes()).filteredOn(n -> n.type() == NodeType.PROJECT)
            .as("exactly one project root node").hasSize(1);

        var types = model.nodes().stream().map(ProjectNode::type).distinct().toList();
        assertThat(types).contains(NodeType.PROJECT, NodeType.PACKAGE, NodeType.COMPONENT,
            NodeType.ENDPOINT, NodeType.ENTRYPOINT, NodeType.DEPENDENCY);

        var edgeTypes = model.edges().stream().map(ProjectEdge::type).distinct().toList();
        assertThat(edgeTypes).contains(EdgeType.CONTAINS, EdgeType.EXPOSES, EdgeType.DEPENDS_ON);

        // Every depends-on edge originates from the single project node (MVP).
        var projectId = model.nodes().stream()
            .filter(n -> n.type() == NodeType.PROJECT).findFirst().orElseThrow().id();
        assertThat(model.edges()).filteredOn(e -> e.type() == EdgeType.DEPENDS_ON)
            .allSatisfy(e -> assertThat(e.from()).isEqualTo(projectId));

        // Components carry a source path with a line range.
        assertThat(model.nodes()).filteredOn(n -> n.type() == NodeType.COMPONENT)
            .allSatisfy(n -> {
                assertThat(n.source()).isNotNull();
                assertThat(n.source().path()).isNotBlank();
                assertThat(n.contentHash()).isNotBlank();
            });
        assertThat(model.nodes()).filteredOn(n -> n.type() == NodeType.COMPONENT)
            .anySatisfy(n -> assertThat(n.source().startLine()).isNotNull());
    }

    @Test
    void starterLibraryHasNoMainEntryPointButHasComponentsAndDependencies() throws Exception {
        var model = assembler.assemble(scan("spring-boot-starter"), FIXED_TS);

        // A starter library has no application main; it may still surface a
        // "config" entry point for its auto-configuration class.
        assertThat(model.nodes())
            .as("starter library has no application main entry point")
            .noneMatch(n -> n.type() == NodeType.ENTRYPOINT && "main".equals(n.name()));
        assertThat(model.nodes()).filteredOn(n -> n.type() == NodeType.COMPONENT).isNotEmpty();
        assertThat(model.nodes()).filteredOn(n -> n.type() == NodeType.DEPENDENCY).isNotEmpty();
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        var model = assembler.assemble(scan("spring-boot"), FIXED_TS);
        var json = new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.INDENT_OUTPUT);

        var serialized = json.writeValueAsString(model);
        var restored = json.readValue(serialized, ProjectModel.class);

        assertThat(restored).isEqualTo(model);
        assertThat(serialized).contains("\"depends-on\"").contains("\"component\"");
    }
}
