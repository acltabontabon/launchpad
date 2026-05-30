package com.acltabontabon.launchpad.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.scanner.PackageSummary;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectContextAssemblerTest {

    private final ProjectContextAssembler assembler = new ProjectContextAssembler();
    private final ObjectMapper mapper = new ObjectMapper();

    private static ProjectContext sampleScan() {
        StackProfile stack = new StackProfile("Java", "Maven", "Spring Boot", List.of());
        List<PackageSummary> packages = List.of(
            new PackageSummary("com/acme/web/controller", 3, List.of("OrderController")),
            new PackageSummary("com/acme/service", 4, List.of("OrderService")),
            new PackageSummary("com/acme/repository", 2, List.of("OrderRepository"))
        );
        List<Dependency> deps = List.of(
            new Dependency("spring-boot-starter-web", "4.0.6", "compile")
        );
        return new ProjectContext(
            "acme-orders",
            "/repos/acme-orders",
            stack,
            List.of("OrderController.java", "OrderService.java"),
            List.of("OrderServiceTest"),
            Map.of("main", "com.acme.Application.main"),
            deps,
            Map.of(),
            packages,
            null
        );
    }

    @Test
    void assemblesDeterministicIdentityAndStructure() {
        VirtualProjectContext model = assembler.assemble(sampleScan(), "1.2.0", "2026-05-31T00:00:00Z");

        assertEquals("acme-orders", model.identity().name());
        assertEquals("/repos/acme-orders", model.identity().rootPath());
        assertEquals("1.2.0", model.identity().packVersion());
        assertEquals("2026-05-31T00:00:00Z", model.identity().generatedAt());
        assertFalse(model.identity().contentHash().isBlank(), "content hash must be populated");

        assertEquals("layered", model.architecture().style());
        assertTrue(model.architecture().layers().containsAll(List.of("controller", "service", "repository")));
        assertEquals(3, model.systems().size());
        assertTrue(model.operations().buildCommands().contains("./mvnw test"));
    }

    @Test
    void contentHashIsStableAcrossRuns() {
        String first = assembler.assemble(sampleScan(), "1.2.0", "t1").identity().contentHash();
        String second = assembler.assemble(sampleScan(), "9.9.9", "t2").identity().contentHash();
        // Hash covers deterministic project inputs only, not the timestamp or pack version.
        assertEquals(first, second);
    }

    @Test
    void synthesizedFieldsCarryProvenanceAndConfidence() {
        VirtualProjectContext model = assembler.assemble(sampleScan(), "1.2.0", "2026-05-31T00:00:00Z");

        assertNotNull(model.architecture().confidence(), "architecture must declare a confidence level");
        assertFalse(model.architecture().evidence().isEmpty(), "architecture must show evidence");
        for (SystemComponent system : model.systems()) {
            assertNotNull(system.confidence());
            assertFalse(system.evidence().isEmpty(), "every system must show evidence");
        }
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        VirtualProjectContext model = assembler.assemble(sampleScan(), "1.2.0", "2026-05-31T00:00:00Z");

        String json = mapper.writeValueAsString(model);
        VirtualProjectContext restored = mapper.readValue(json, VirtualProjectContext.class);

        assertEquals(model.identity().name(), restored.identity().name());
        assertEquals(model.identity().contentHash(), restored.identity().contentHash());
        assertEquals(model.architecture().style(), restored.architecture().style());
        assertEquals(model.systems().size(), restored.systems().size());
        assertEquals(model.architecture().confidence(), restored.architecture().confidence());
        assertFalse(restored.architecture().evidence().isEmpty());
    }
}
