package com.acltabontabon.launchpad.tui.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.config.WorkspaceConfigService;
import com.acltabontabon.launchpad.scanner.ProjectSupportDetector;
import com.acltabontabon.launchpad.scanner.ProjectSupportSignal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectDiscoveryTest {

    /** Recognises every directory the build-root pre-filter lets through. */
    private static final ProjectSupportSignal ALWAYS =
        root -> Optional.of(new ProjectSupportSignal.Match("Test"));

    private static ProjectDiscovery discovery(Path configFile) {
        var detector = new ProjectSupportDetector(List.of(ALWAYS));
        return new ProjectDiscovery(detector, new WorkspaceConfigService(configFile));
    }

    private static Path mavenProject(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        return dir;
    }

    private static List<String> discoveredNames(ProjectDiscovery discovery) throws Exception {
        discovery.refresh();
        var deadline = System.currentTimeMillis() + 5_000;
        while (!discovery.hasRun() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(discovery.hasRun()).as("discovery completed").isTrue();
        return discovery.snapshot().stream().map(DiscoveredProject::name).toList();
    }

    private static void writeConfig(Path file, String roots, String extra) throws Exception {
        Files.writeString(file, "workspace:\n  roots:\n    - " + roots + "\n" + extra);
    }

    @Test
    void honorsConfiguredRootsWhenGitGateOff(@TempDir Path tmp) throws Exception {
        var ws = tmp.resolve("ws");
        mavenProject(ws.resolve("app-a"));
        mavenProject(ws.resolve("app-b"));
        var config = tmp.resolve("config.yml");
        writeConfig(config, ws.toString(), "  detectGitOnly: false\n");

        assertThat(discoveredNames(discovery(config)))
            .containsExactlyInAnyOrder("app-a", "app-b");
    }

    @Test
    void detectGitOnlyTrueExcludesNonGitProject(@TempDir Path tmp) throws Exception {
        var ws = tmp.resolve("ws");
        mavenProject(ws.resolve("plain"));
        var withGit = mavenProject(ws.resolve("repo"));
        Files.createDirectory(withGit.resolve(".git"));
        var config = tmp.resolve("config.yml");
        writeConfig(config, ws.toString(), "  detectGitOnly: true\n");

        assertThat(discoveredNames(discovery(config))).containsExactly("repo");
    }

    @Test
    void ignoredDirectoryIsPruned(@TempDir Path tmp) throws Exception {
        var ws = tmp.resolve("ws");
        mavenProject(ws.resolve("keep"));
        mavenProject(ws.resolve("hide"));
        var config = tmp.resolve("config.yml");
        writeConfig(config, ws.toString(),
            "  detectGitOnly: false\n  ignored:\n    - " + ws.resolve("hide") + "\n");

        assertThat(discoveredNames(discovery(config))).containsExactly("keep");
    }

    @Test
    void ignoringParentPrunesRootEntirely(@TempDir Path tmp) throws Exception {
        var ws = tmp.resolve("ws");
        mavenProject(ws.resolve("app"));
        var config = tmp.resolve("config.yml");
        writeConfig(config, ws.toString(),
            "  detectGitOnly: false\n  ignored:\n    - " + ws + "\n");

        assertThat(discoveredNames(discovery(config))).isEmpty();
    }

    @Test
    void depthBoundsTheWalk(@TempDir Path tmp) throws Exception {
        var ws = tmp.resolve("ws");
        mavenProject(ws.resolve("a/nested"));   // two levels below the root

        var shallow = tmp.resolve("shallow.yml");
        writeConfig(shallow, ws.toString(), "  detectGitOnly: false\n  depth: 1\n");
        assertThat(discoveredNames(discovery(shallow))).isEmpty();

        var deep = tmp.resolve("deep.yml");
        writeConfig(deep, ws.toString(), "  detectGitOnly: false\n  depth: 3\n");
        assertThat(discoveredNames(discovery(deep))).containsExactly("nested");
    }
}
