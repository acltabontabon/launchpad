package com.acltabontabon.launchpad.standards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The #84 contract: every {@code standards-pack.yml} must declare a supported
 * {@code schemaVersion}. A missing or out-of-range version is rejected at load time
 * with {@link IncompatiblePackSchemaException} - across every entry point - rather
 * than read silently against a format this Launchpad does not understand.
 */
class StandardsPackSchemaVersionTest {

    @Test
    void missingSchemaVersionIsRejected(@TempDir Path projectRoot) throws Exception {
        writePack(projectRoot, null);
        var loader = new StandardsLoader(noRemote(), null);

        assertThatThrownBy(() -> loader.loadResolvedStandards(projectRoot))
            .isInstanceOf(IncompatiblePackSchemaException.class)
            .hasMessageContaining("missing the required schemaVersion");
    }

    @Test
    void schemaVersionAboveSupportedIsRejected(@TempDir Path projectRoot) throws Exception {
        writePack(projectRoot, 99);
        var loader = new StandardsLoader(noRemote(), null);

        assertThatThrownBy(() -> loader.loadResolvedStandards(projectRoot))
            .isInstanceOf(IncompatiblePackSchemaException.class)
            .hasMessageContaining("schemaVersion 99");
    }

    @Test
    void schemaVersionBelowSupportedIsRejected(@TempDir Path projectRoot) throws Exception {
        writePack(projectRoot, 0);
        var loader = new StandardsLoader(noRemote(), null);

        assertThatThrownBy(() -> loader.loadResolvedStandards(projectRoot))
            .isInstanceOf(IncompatiblePackSchemaException.class);
    }

    @Test
    void supportedSchemaVersionLoadsNormally(@TempDir Path projectRoot) throws Exception {
        writePack(projectRoot, 1);
        var loader = new StandardsLoader(noRemote(), null);

        var resolved = loader.loadResolvedStandards(projectRoot);
        assertThat(resolved.rules()).extracting(Rule::id).containsExactly("the.rule");
        assertThat(resolved.source().pack()).isEqualTo("acme-pack");
    }

    @Test
    void rejectionAlsoFiresThroughProjectionResolution(@TempDir Path projectRoot) throws Exception {
        // The projections path has a soft catch that falls back to the default;
        // it must not swallow an incompatible-schema rejection.
        writePack(projectRoot, 99);
        var loader = new StandardsLoader(noRemote(), null);

        assertThatThrownBy(() -> loader.loadProjectionIds(projectRoot))
            .isInstanceOf(IncompatiblePackSchemaException.class);
    }

    /** Writes a pack whose manifest carries {@code schemaVersion} (or omits it when null). */
    private static void writePack(Path projectRoot, Integer schemaVersion) throws Exception {
        var dir = projectRoot.resolve(".launchpad/standards");
        Files.createDirectories(dir.resolve("rules"));
        String versionLine = schemaVersion == null ? "" : "schemaVersion: " + schemaVersion + "\n";
        Files.writeString(dir.resolve("standards-pack.yml"), versionLine + """
            id: acme-pack
            version: 1.0.0
            includes:
              rules:
                - rules/main.yml
            """);
        Files.writeString(dir.resolve("rules/main.yml"), """
            rules:
              - id: the.rule
                title: The Rule
                severity: HIGH
                content: Do the thing.
            """);
    }

    private static RemoteStandardsFetcher noRemote() {
        var m = mock(RemoteStandardsFetcher.class);
        when(m.ensureCache()).thenReturn(Optional.empty());
        return m;
    }
}
