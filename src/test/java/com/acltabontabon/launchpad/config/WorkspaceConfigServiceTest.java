package com.acltabontabon.launchpad.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceConfigServiceTest {

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void absentConfigUsesDefaults(@TempDir Path tmp) {
        var svc = new WorkspaceConfigService(tmp.resolve("config.yml"));

        var expected = WorkspaceConfigService.DEFAULT_ROOT_NAMES.stream()
            .map(HOME::resolve)
            .toList();
        assertThat(svc.resolvedRoots()).containsExactlyElementsOf(expected);
        assertThat(svc.depth()).isEqualTo(3);
        assertThat(svc.detectGitOnly()).isTrue();
        assertThat(svc.ignoredPaths()).isEmpty();
        assertThat(svc.lastLoadError()).isNull();
        assertThat(svc.rootsExplicitlySet()).isFalse();
    }

    @Test
    void rootsReplacesDefaults(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("config.yml");
        Files.writeString(file, """
            workspace:
              roots:
                - ~/foo
                - /abs/bar
            """);
        var svc = new WorkspaceConfigService(file);

        assertThat(svc.resolvedRoots())
            .containsExactly(HOME.resolve("foo"), Path.of("/abs/bar"));
        assertThat(svc.rootsExplicitlySet()).isTrue();
    }

    @Test
    void additionalRootsAppendsWhenRootsUnset(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("config.yml");
        Files.writeString(file, """
            workspace:
              additionalRoots:
                - /work/projects
            """);
        var svc = new WorkspaceConfigService(file);

        assertThat(svc.resolvedRoots())
            .hasSize(WorkspaceConfigService.DEFAULT_ROOT_NAMES.size() + 1)
            .startsWith(HOME.resolve("Workspace"))
            .endsWith(Path.of("/work/projects"));
    }

    @Test
    void rootsSetIgnoresAdditionalRoots(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("config.yml");
        Files.writeString(file, """
            workspace:
              roots:
                - ~/only
              additionalRoots:
                - /work/projects
            """);
        var svc = new WorkspaceConfigService(file);

        assertThat(svc.resolvedRoots()).containsExactly(HOME.resolve("only"));
    }

    @Test
    void emptyRootsMeansNoRoots(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("config.yml");
        Files.writeString(file, """
            workspace:
              roots: []
            """);
        var svc = new WorkspaceConfigService(file);

        assertThat(svc.rootsExplicitlySet()).isTrue();
        assertThat(svc.resolvedRoots()).isEmpty();
    }

    @Test
    void tildeAndAbsoluteExpansion(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("config.yml");
        Files.writeString(file, """
            workspace:
              roots:
                - "~"
                - ~/Workspace
                - /work/x
              ignored:
                - ~/dev/legacy
            """);
        var svc = new WorkspaceConfigService(file);

        assertThat(svc.resolvedRoots())
            .containsExactly(HOME, HOME.resolve("Workspace"), Path.of("/work/x"));
        assertThat(svc.ignoredPaths()).containsExactly(HOME.resolve("dev/legacy"));
    }

    @Test
    void duplicatesCollapseAfterExpansion(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("config.yml");
        Files.writeString(file, """
            workspace:
              roots:
                - ~/dup
                - ~/dup
            """);
        var svc = new WorkspaceConfigService(file);

        assertThat(svc.resolvedRoots()).containsExactly(HOME.resolve("dup"));
    }

    @Test
    void depthClampedOnRead(@TempDir Path tmp) throws Exception {
        var low = tmp.resolve("low.yml");
        Files.writeString(low, "workspace:\n  depth: 0\n");
        assertThat(new WorkspaceConfigService(low).depth()).isEqualTo(1);

        var high = tmp.resolve("high.yml");
        Files.writeString(high, "workspace:\n  depth: 99\n");
        assertThat(new WorkspaceConfigService(high).depth()).isEqualTo(5);

        var ok = tmp.resolve("ok.yml");
        Files.writeString(ok, "workspace:\n  depth: 4\n");
        assertThat(new WorkspaceConfigService(ok).depth()).isEqualTo(4);
    }

    @Test
    void detectGitOnlyDefaultsTrueAndReadsFalse(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("config.yml");
        Files.writeString(file, "workspace:\n  detectGitOnly: false\n");

        assertThat(new WorkspaceConfigService(file).detectGitOnly()).isFalse();
    }

    @Test
    void addRootSeedsDefaultsAndSwitchesToReplaceMode(@TempDir Path tmp) {
        var file = tmp.resolve("config.yml");
        var svc = new WorkspaceConfigService(file);

        svc.addRoot("~/clients");

        assertThat(svc.rootsExplicitlySet()).isTrue();
        assertThat(svc.rawRoots())
            .contains("~/Workspace", "~/clients")
            .hasSize(WorkspaceConfigService.DEFAULT_ROOT_NAMES.size() + 1);
        assertThat(svc.resolvedRoots()).contains(HOME.resolve("clients"));
    }

    @Test
    void addRootIsIdempotent(@TempDir Path tmp) {
        var svc = new WorkspaceConfigService(tmp.resolve("config.yml"));
        svc.addRoot("~/clients");
        var size = svc.rawRoots().size();
        svc.addRoot("  ~/clients  ");

        assertThat(svc.rawRoots()).hasSize(size);
    }

    @Test
    void blankMutationsAreNoOps(@TempDir Path tmp) {
        var file = tmp.resolve("config.yml");
        var svc = new WorkspaceConfigService(file);

        svc.addRoot("   ");
        svc.addIgnored("");

        assertThat(svc.rootsExplicitlySet()).isFalse();
        assertThat(svc.rawIgnored()).isEmpty();
    }

    @Test
    void removeRootReturnsWhetherRemoved(@TempDir Path tmp) {
        var svc = new WorkspaceConfigService(tmp.resolve("config.yml"));
        svc.addRoot("~/clients");

        assertThat(svc.removeRoot("~/clients")).isTrue();
        assertThat(svc.removeRoot("~/never")).isFalse();
        assertThat(svc.rawRoots()).doesNotContain("~/clients");
    }

    @Test
    void mutatorsPersistAcrossInstances(@TempDir Path tmp) {
        var file = tmp.resolve("config.yml");
        var first = new WorkspaceConfigService(file);
        first.addRoot("~/clients");
        first.addIgnored("~/dev/legacy");
        first.setDepth(99);
        first.setDetectGitOnly(false);

        var second = new WorkspaceConfigService(file);

        assertThat(second.rawRoots()).contains("~/clients");
        assertThat(second.rawIgnored()).containsExactly("~/dev/legacy");
        assertThat(second.rawDepth()).isEqualTo(5);            // stored clamped
        assertThat(second.rawDetectGitOnly()).isFalse();
        assertThat(second.detectGitOnly()).isFalse();
    }

    @Test
    void removeIgnoredReturnsWhetherRemoved(@TempDir Path tmp) {
        var svc = new WorkspaceConfigService(tmp.resolve("config.yml"));
        svc.addIgnored("~/dev/legacy");

        assertThat(svc.removeIgnored("~/dev/legacy")).isTrue();
        assertThat(svc.removeIgnored("~/dev/legacy")).isFalse();
        assertThat(svc.rawIgnored()).isEmpty();
    }

    @Test
    void corruptConfigFallsBackToDefaultsWithLastLoadError(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("config.yml");
        Files.writeString(file, "workspace: : : not valid yaml {{{");
        var svc = new WorkspaceConfigService(file);

        assertThat(svc.depth()).isEqualTo(3);
        assertThat(svc.rootsExplicitlySet()).isFalse();
        assertThat(svc.lastLoadError()).contains(file.toString());
    }

    @Test
    void unknownKeysAreTolerated(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("config.yml");
        Files.writeString(file, """
            workspace:
              roots:
                - ~/foo
              mysteryKey: ignored
            topLevelMystery: also ignored
            """);
        var svc = new WorkspaceConfigService(file);

        assertThat(svc.resolvedRoots()).containsExactly(HOME.resolve("foo"));
        assertThat(svc.lastLoadError()).isNull();
    }

    @Test
    void firstWriteCreatesFileAndParentDir(@TempDir Path tmp) {
        var file = tmp.resolve("nested/dir/config.yml");
        var svc = new WorkspaceConfigService(file);

        svc.setDepth(2);

        assertThat(Files.isRegularFile(file)).isTrue();
    }
}
