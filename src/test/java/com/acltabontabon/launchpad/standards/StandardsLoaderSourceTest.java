package com.acltabontabon.launchpad.standards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acltabontabon.launchpad.standards.index.StandardsSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the resolve-once invariant of {@link StandardsLoader#loadResolvedStandards}:
 * the returned {@code source} always describes exactly the returned {@code rules},
 * resolved from a single winning source directory per the existing precedence.
 */
class StandardsLoaderSourceTest {

    @Test
    void manifestPackReportsPackVersionAndOrigin(@TempDir Path projectRoot) throws Exception {
        var dir = projectRoot.resolve(".launchpad/standards");
        Files.createDirectories(dir.resolve("rules"));
        Files.writeString(dir.resolve("standards-pack.yml"), """
            id: acme-pack
            version: 2.3.1
            includes:
              rules:
                - rules/java.yml
            """);
        Files.writeString(dir.resolve("rules/java.yml"), flatRules("java.rule-a"));

        var loader = new StandardsLoader(noRemote(), null);
        var resolved = loader.loadResolvedStandards(projectRoot);

        assertThat(resolved.rules()).extracting(Rule::id).containsExactly("java.rule-a");
        assertThat(resolved.source()).isEqualTo(
            new StandardsSource("acme-pack", "2.3.1", StandardsSource.ORIGIN_LOCAL_OVERRIDE));
    }

    @Test
    void legacyFlatReportsNullPackWithOrigin(@TempDir Path projectRoot) throws Exception {
        var dir = projectRoot.resolve(".launchpad/standards");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("rules.yml"), flatRules("flat.rule"));

        var loader = new StandardsLoader(noRemote(), null);
        var resolved = loader.loadResolvedStandards(projectRoot);

        assertThat(resolved.rules()).extracting(Rule::id).containsExactly("flat.rule");
        assertThat(resolved.source())
            .isEqualTo(new StandardsSource(null, null, StandardsSource.ORIGIN_LOCAL_OVERRIDE));
    }

    @Test
    void noRulesReportsNullSource(@TempDir Path projectRoot) {
        var loader = new StandardsLoader(noRemote(), null);
        var resolved = loader.loadResolvedStandards(projectRoot);

        assertThat(resolved.rules()).isEmpty();
        assertThat(resolved.source()).isNull();
    }

    @Test
    void remoteCacheWinsOverLocalOverrideForBothRulesAndSource(@TempDir Path projectRoot,
                                                               @TempDir Path remoteCache) throws Exception {
        // Remote cache (higher precedence) carries rule A; the per-project
        // override carries rule B. Rules and source must agree on the winner.
        Files.writeString(remoteCache.resolve("rules.yml"), flatRules("remote.rule-a"));
        var override = projectRoot.resolve(".launchpad/standards");
        Files.createDirectories(override);
        Files.writeString(override.resolve("rules.yml"), flatRules("local.rule-b"));

        var loader = new StandardsLoader(remoteAt(remoteCache), null);
        var resolved = loader.loadResolvedStandards(projectRoot);

        assertThat(resolved.rules()).extracting(Rule::id).containsExactly("remote.rule-a");
        assertThat(resolved.source().origin()).isEqualTo(StandardsSource.ORIGIN_REMOTE_CACHE);
    }

    private static String flatRules(String ruleId) {
        return """
            version: 1
            rules:
              - id: %s
                title: Rule %s
                severity: HIGH
                content: Do the thing.
            """.formatted(ruleId, ruleId);
    }

    private static RemoteStandardsFetcher noRemote() {
        var m = mock(RemoteStandardsFetcher.class);
        when(m.ensureCache()).thenReturn(Optional.empty());
        return m;
    }

    private static RemoteStandardsFetcher remoteAt(Path cache) {
        var m = mock(RemoteStandardsFetcher.class);
        when(m.ensureCache()).thenReturn(Optional.of(cache));
        return m;
    }
}
