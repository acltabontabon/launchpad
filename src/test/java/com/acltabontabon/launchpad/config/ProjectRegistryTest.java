package com.acltabontabon.launchpad.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectRegistryTest {

    @Test
    void registerCreatesEntryWithDerivedName(@TempDir Path tmp) throws Exception {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));
        var project = Files.createDirectory(tmp.resolve("my-app"));

        var entry = registry.register(project, "Java/Spring Boot");

        assertThat(entry.name()).isEqualTo("my-app");
        assertThat(entry.path()).isEqualTo(project.toAbsolutePath().normalize().toString());
        assertThat(entry.stack()).isEqualTo("Java/Spring Boot");
        assertThat(entry.lastScannedAt()).isNotNull();
    }

    @Test
    void registerSamePathUpdatesInPlace(@TempDir Path tmp) throws Exception {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));
        var project = Files.createDirectory(tmp.resolve("my-app"));

        var first = registry.register(project, "Java/Spring Boot");
        Thread.sleep(5);
        var second = registry.register(project, "Java/Spring Boot");

        assertThat(registry.all()).hasSize(1);
        assertThat(second.addedAt()).isEqualTo(first.addedAt());
        assertThat(second.lastScannedAt()).isAfter(first.lastScannedAt());
    }

    @Test
    void registerDisambiguatesCollidingNames(@TempDir Path tmp) throws Exception {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));
        var a = Files.createDirectories(tmp.resolve("a/my-app"));
        var b = Files.createDirectories(tmp.resolve("b/my-app"));

        registry.register(a, "stack-a");
        var second = registry.register(b, "stack-b");

        assertThat(second.name()).isEqualTo("my-app-2");
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void findByNameIsCaseInsensitive(@TempDir Path tmp) throws Exception {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));
        var project = Files.createDirectory(tmp.resolve("Launchpad"));
        registry.register(project, "Java");

        assertThat(registry.findByName("launchpad")).isPresent();
        assertThat(registry.findByName("LAUNCHPAD")).isPresent();
        assertThat(registry.findByName("missing")).isEmpty();
    }

    @Test
    void resolveToPathReturnsRegistryPathForKnownName(@TempDir Path tmp) throws Exception {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));
        var project = Files.createDirectory(tmp.resolve("widgets"));
        registry.register(project, "Java");

        var resolved = registry.resolveToPath("widgets");

        assertThat(resolved).contains(project.toAbsolutePath().normalize());
    }

    @Test
    void resolveToPathPassesThroughAbsolutePaths(@TempDir Path tmp) {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));

        var resolved = registry.resolveToPath("/tmp/anywhere");

        assertThat(resolved).contains(Path.of("/tmp/anywhere"));
    }

    @Test
    void resolveToPathReturnsEmptyForUnknownName(@TempDir Path tmp) {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));

        assertThat(registry.resolveToPath("never-registered")).isEmpty();
    }

    @Test
    void persistsAcrossInstances(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("projects.json");
        var first = new ProjectRegistry(file);
        var project = Files.createDirectory(tmp.resolve("survives"));
        first.register(project, "Java");

        var second = new ProjectRegistry(file);

        assertThat(second.findByName("survives")).isPresent();
    }

    @Test
    void removeDeletesByName(@TempDir Path tmp) throws Exception {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));
        var project = Files.createDirectory(tmp.resolve("doomed"));
        registry.register(project, "Java");

        assertThat(registry.remove("doomed")).isTrue();
        assertThat(registry.findByName("doomed")).isEmpty();
    }

    @Test
    void pruneRemovesEntriesWhosePathIsGone(@TempDir Path tmp) throws Exception {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));
        var alive = Files.createDirectory(tmp.resolve("alive"));
        var doomed = Files.createDirectory(tmp.resolve("doomed"));
        registry.register(alive, "Java");
        registry.register(doomed, "Java");
        Files.delete(doomed);

        var pruned = registry.pruneMissing();

        assertThat(pruned).containsExactly("doomed");
        assertThat(registry.findByName("alive")).isPresent();
        assertThat(registry.findByName("doomed")).isEmpty();
    }

    @Test
    void allOrdersByLastScannedDescThenName(@TempDir Path tmp) throws Exception {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));
        var older = Files.createDirectory(tmp.resolve("older"));
        var newer = Files.createDirectory(tmp.resolve("newer"));
        registry.register(older, "Java");
        Thread.sleep(10);
        registry.register(newer, "Java");

        var ordered = registry.all();

        assertThat(ordered).extracting(RegisteredProject::name)
            .containsExactly("newer", "older");
    }

    @Test
    void corruptFileFallsBackToEmpty(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("projects.json");
        Files.writeString(file, "{ not valid json");

        var registry = new ProjectRegistry(file);

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void allOverlaysProjectYmlMetadata(@TempDir Path tmp) throws Exception {
        var registry = new ProjectRegistry(tmp.resolve("projects.json"));
        var project = Files.createDirectory(tmp.resolve("shop-api"));
        Files.createDirectory(project.resolve(".launchpad"));
        Files.writeString(project.resolve(".launchpad/project.yml"), """
            tags: [backend, payments]
            workspace: shop
            relatedTo: [shop-frontend]
            """);
        registry.register(project, "Java/Spring Boot");

        var overlaid = registry.findByName("shop-api").orElseThrow();
        assertThat(overlaid.tags()).isEmpty();  // findByName does not overlay - lightweight lookup

        var snapshot = registry.all();
        assertThat(snapshot).hasSize(1);
        var first = snapshot.get(0);
        assertThat(first.workspace()).isEqualTo("shop");
        assertThat(first.tags()).containsExactly("backend", "payments");
        assertThat(first.relatedTo()).containsExactly("shop-frontend");
    }

    @Test
    void overlayDoesNotMutateRegistryFile(@TempDir Path tmp) throws Exception {
        var registryFile = tmp.resolve("projects.json");
        var registry = new ProjectRegistry(registryFile);
        var project = Files.createDirectory(tmp.resolve("widgets"));
        Files.createDirectory(project.resolve(".launchpad"));
        Files.writeString(project.resolve(".launchpad/project.yml"), "workspace: misc\n");
        registry.register(project, "Java");

        var beforeBytes = Files.readString(registryFile);
        var overlaid = registry.all();
        var afterBytes = Files.readString(registryFile);

        assertThat(overlaid).first().extracting(RegisteredProject::workspace).isEqualTo("misc");
        assertThat(afterBytes).isEqualTo(beforeBytes);
        assertThat(afterBytes).doesNotContain("workspace");
    }
}
