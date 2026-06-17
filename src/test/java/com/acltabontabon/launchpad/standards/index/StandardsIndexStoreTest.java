package com.acltabontabon.launchpad.standards.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandardsIndexStoreTest {

    private final StandardsIndexStore store = new StandardsIndexStore();

    private static StandardsIndex sampleIndex() {
        var entry = new StandardsRuleEntry(
            "java.no-field-injection", "No field injection", "HIGH", Scope.empty(),
            "Use constructor injection.", "Field injection hides dependencies.",
            new StandardsSource("acme-pack", "1.0.0", StandardsSource.ORIGIN_LOCAL_OVERRIDE),
            true, "forbid-pattern", 10, "deadbeef");
        return new StandardsIndex(StandardsIndex.SCHEMA_VERSION, "2026-01-01T00:00:00Z",
            entry.source(), List.of(entry));
    }

    @Test
    void savesToLaunchpadDirAndRoundTrips(@TempDir Path projectRoot) {
        var index = sampleIndex();

        var written = store.save(projectRoot, index);

        assertThat(written).isEqualTo(projectRoot.resolve(".launchpad").resolve("standards.index.json"));
        assertThat(Files.isRegularFile(written)).isTrue();
        assertThat(store.load(projectRoot)).contains(index);
    }

    @Test
    void createsLaunchpadDirWhenMissing(@TempDir Path projectRoot) {
        assertThat(Files.exists(projectRoot.resolve(".launchpad"))).isFalse();

        store.save(projectRoot, sampleIndex());

        assertThat(Files.isDirectory(projectRoot.resolve(".launchpad"))).isTrue();
    }

    @Test
    void absentFileLoadsEmpty(@TempDir Path projectRoot) {
        assertThat(store.load(projectRoot)).isEmpty();
    }
}
