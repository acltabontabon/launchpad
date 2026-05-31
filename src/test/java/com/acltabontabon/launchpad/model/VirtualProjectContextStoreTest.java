package com.acltabontabon.launchpad.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VirtualProjectContextStoreTest {

    private final VirtualProjectContextStore store = new VirtualProjectContextStore();

    private static VirtualProjectContext sampleModel() {
        ProjectIdentity identity =
            new ProjectIdentity("acme", "/repos/acme", "Spring Boot / Java / Maven",
                "2026-05-31T00:00:00Z", "1.0.0", "abc123");
        Architecture architecture = new Architecture(
            "layered", List.of("controller", "service"), List.of("com/acme/web"),
            java.util.Map.of(), "", List.of(), Confidence.DETERMINISTIC,
            List.of(Evidence.of("com/acme/web", "3 files")));
        SystemComponent system = new SystemComponent(
            "com-acme-web", "com/acme/web", "", List.of(), List.of("com/acme/web"),
            List.of(), Confidence.DETERMINISTIC, List.of(Evidence.of("com/acme/web", "3 files")));
        return new VirtualProjectContext(identity, architecture, List.of(system),
            List.of(), StandardsProfile.empty(), Operations.empty(),
            DocumentationMap.empty(), List.of());
    }

    @Test
    void savesToExpectedPathAndRoundTrips(@TempDir Path root) {
        Path written = store.save(root, sampleModel());

        assertTrue(Files.isRegularFile(written), "model file should exist");
        assertEquals(root.resolve(".launchpad").resolve("project-context.json"), written);

        VirtualProjectContext restored = store.load(root).orElseThrow();
        assertEquals("acme", restored.identity().name());
        assertEquals("abc123", restored.identity().contentHash());
        assertEquals("layered", restored.architecture().style());
        assertEquals(1, restored.systems().size());
        assertEquals(Confidence.DETERMINISTIC, restored.architecture().confidence());
        assertFalse(restored.architecture().evidence().isEmpty());
    }

    @Test
    void loadReturnsEmptyWhenMissing(@TempDir Path root) {
        assertTrue(store.load(root).isEmpty());
    }
}
