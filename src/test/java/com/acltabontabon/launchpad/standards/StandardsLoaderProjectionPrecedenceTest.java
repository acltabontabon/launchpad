package com.acltabontabon.launchpad.standards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acltabontabon.launchpad.ai.LlmProvider;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the resolution order in {@link StandardsLoader#loadProjectionIds(Path)}:
 * user preference -> manifest field -> claude default. Emission targets are a
 * developer concern (which AI tools they use locally) so the user pref wins
 * over a tech-lead-authored manifest.
 */
class StandardsLoaderProjectionPrecedenceTest {

    @Test
    void userPreferenceOverridesManifest(@TempDir Path projectRoot) throws Exception {
        writeManifest(projectRoot, """
            id: org-pack
            name: Org Pack
            projections:
              - claude
              - cursor
            """);
        var loader = new StandardsLoader(noRemote(),
            settingsWithProjections(Set.of("claude")));

        assertThat(loader.loadProjectionIds(projectRoot)).containsExactly("claude");
    }

    @Test
    void manifestUsedWhenUserPreferenceAbsent(@TempDir Path projectRoot) throws Exception {
        writeManifest(projectRoot, """
            id: org-pack
            name: Org Pack
            projections:
              - claude
              - cursor
            """);
        var loader = new StandardsLoader(noRemote(),
            settingsWithProjections(null));

        assertThat(loader.loadProjectionIds(projectRoot)).containsExactly("claude", "cursor");
    }

    @Test
    void emptyUserPreferenceDisablesAllProjections(@TempDir Path projectRoot) throws Exception {
        // Tech lead's manifest says "claude + cursor"; the developer explicitly
        // opted out of everything. User intent wins.
        writeManifest(projectRoot, """
            id: org-pack
            name: Org Pack
            projections:
              - claude
              - cursor
            """);
        var loader = new StandardsLoader(noRemote(),
            settingsWithProjections(Set.of()));

        assertThat(loader.loadProjectionIds(projectRoot)).isEmpty();
    }

    @Test
    void defaultUsedWhenNeitherSettingNorManifestPresent(@TempDir Path projectRoot) {
        var loader = new StandardsLoader(noRemote(),
            settingsWithProjections(null));

        assertThat(loader.loadProjectionIds(projectRoot)).containsExactly("claude");
    }

    private static RemoteStandardsFetcher noRemote() {
        var m = mock(RemoteStandardsFetcher.class);
        when(m.ensureCache()).thenReturn(Optional.empty());
        return m;
    }

    private static LaunchpadSettings settingsWithProjections(Set<String> projections) {
        var settings = mock(LaunchpadSettings.class);
        when(settings.snapshot()).thenReturn(new Snapshot(
            LlmProvider.AUTO, "http://localhost:11434", "qwen2.5-coder:7b",
            null, null, projections
        ));
        return settings;
    }

    private static void writeManifest(Path projectRoot, String body) throws Exception {
        var dir = projectRoot.resolve(".launchpad/standards");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("standards-pack.yml"), body);
    }
}
