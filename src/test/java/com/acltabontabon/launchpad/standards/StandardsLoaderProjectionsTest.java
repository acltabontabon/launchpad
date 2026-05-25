package com.acltabontabon.launchpad.standards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandardsLoaderProjectionsTest {

    private final RemoteStandardsFetcher noRemote = mockFetcher();
    private final StandardsLoader loader = new StandardsLoader(noRemote);

    @Test
    void defaultsToClaudeWhenNoManifestExists(@TempDir Path projectRoot) {
        assertThat(loader.loadProjectionIds(projectRoot))
            .containsExactly("claude");
    }

    @Test
    void defaultsToClaudeWhenProjectionsFieldAbsent(@TempDir Path projectRoot) throws Exception {
        writeManifest(projectRoot, """
            id: test-pack
            name: Test Pack
            adapters: {}
            """);

        assertThat(loader.loadProjectionIds(projectRoot))
            .containsExactly("claude");
    }

    @Test
    void defaultsToClaudeWhenProjectionsExplicitlyNull(@TempDir Path projectRoot) throws Exception {
        writeManifest(projectRoot, """
            id: test-pack
            name: Test Pack
            projections: ~
            """);

        assertThat(loader.loadProjectionIds(projectRoot))
            .containsExactly("claude");
    }

    @Test
    void emptyListDisablesAllProjections(@TempDir Path projectRoot) throws Exception {
        writeManifest(projectRoot, """
            id: test-pack
            name: Test Pack
            projections: []
            """);

        assertThat(loader.loadProjectionIds(projectRoot)).isEmpty();
    }

    @Test
    void explicitClaudeListResolvesToClaudeOnly(@TempDir Path projectRoot) throws Exception {
        writeManifest(projectRoot, """
            id: test-pack
            name: Test Pack
            projections:
              - claude
            """);

        assertThat(loader.loadProjectionIds(projectRoot))
            .containsExactly("claude");
    }

    @Test
    void multipleProjectionsReportedInOrder(@TempDir Path projectRoot) throws Exception {
        writeManifest(projectRoot, """
            id: test-pack
            name: Test Pack
            projections:
              - claude
              - cursor
            """);

        assertThat(loader.loadProjectionIds(projectRoot))
            .containsExactly("claude", "cursor");
    }

    @Test
    void malformedProjectionsValueFallsBackToDefault(@TempDir Path projectRoot) throws Exception {
        // A scalar where a sequence is expected. Loader must not blow up
        // generation - log a warning and fall back to the claude default.
        writeManifest(projectRoot, """
            id: test-pack
            name: Test Pack
            projections: not-a-list
            """);

        assertThat(loader.loadProjectionIds(projectRoot))
            .containsExactly("claude");
    }

    private static RemoteStandardsFetcher mockFetcher() {
        var m = mock(RemoteStandardsFetcher.class);
        when(m.ensureCache()).thenReturn(Optional.empty());
        return m;
    }

    private static void writeManifest(Path projectRoot, String body) throws Exception {
        var dir = projectRoot.resolve(".launchpad/standards");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("standards-pack.yml"), body);
    }
}
