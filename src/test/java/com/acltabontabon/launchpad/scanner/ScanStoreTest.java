package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanStoreTest {

    private final ScanStore store = new ScanStore();

    @Test
    void roundTripsAllFields(@TempDir Path projectRoot) {
        var original = sampleContext();

        store.save(projectRoot, original);
        var loaded = store.load(projectRoot).orElseThrow();

        assertThat(loaded).isEqualTo(original);
    }

    @Test
    void writesToDotLaunchpadScanJson(@TempDir Path projectRoot) {
        store.save(projectRoot, sampleContext());

        assertThat(projectRoot.resolve(".launchpad/scan.json")).exists();
    }

    @Test
    void loadReturnsEmptyWhenFileMissing(@TempDir Path projectRoot) {
        assertThat(store.load(projectRoot)).isEmpty();
    }

    @Test
    void loadReturnsEmptyWhenFileCorrupt(@TempDir Path projectRoot) throws Exception {
        var path = projectRoot.resolve(".launchpad/scan.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{ this is not valid json");

        assertThat(store.load(projectRoot)).isEmpty();
    }

    @Test
    void freshIsTrueAfterRecentWrite(@TempDir Path projectRoot) {
        store.save(projectRoot, sampleContext());

        assertThat(store.isFresh(projectRoot, Duration.ofHours(1))).isTrue();
    }

    @Test
    void freshIsFalseWhenFileMissing(@TempDir Path projectRoot) {
        assertThat(store.isFresh(projectRoot, Duration.ofHours(1))).isFalse();
    }

    @Test
    void freshIsFalseWhenOlderThanMaxAge(@TempDir Path projectRoot) throws Exception {
        store.save(projectRoot, sampleContext());
        var path = store.scanFile(projectRoot);
        var twoHoursAgo = java.nio.file.attribute.FileTime.from(
            java.time.Instant.now().minus(Duration.ofHours(2)));
        Files.setLastModifiedTime(path, twoHoursAgo);

        assertThat(store.isFresh(projectRoot, Duration.ofHours(1))).isFalse();
    }

    private static ProjectContext sampleContext() {
        return new ProjectContext(
            "demo",
            "/tmp/demo",
            new StackProfile("Java", "Maven", "Spring Boot", List.of("jvm17", "graalvm")),
            List.of("src/main/java/App.java", "src/main/java/api/UserController.java"),
            List.of("AppTest"),
            Map.of("main", "src/main/java/App.java"),
            List.of(
                new Dependency("org.springframework.boot:spring-boot-starter", "4.0.6", "runtime"),
                new Dependency("org.junit.jupiter:junit-jupiter", "5.10.0", "test")
            ),
            Map.of("pom.xml", "<project>...</project>"),
            List.of(new PackageSummary("src/main/java/api", 3, List.of("UserController", "OrderController"))),
            "Existing CLAUDE.md preview text."
        );
    }
}
