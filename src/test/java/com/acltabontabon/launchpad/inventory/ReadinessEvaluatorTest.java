package com.acltabontabon.launchpad.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.support.LaunchpadVersion;
import com.acltabontabon.launchpad.template.ProvenanceHeader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadinessEvaluatorTest {

    private final ReadinessEvaluator evaluator = new ReadinessEvaluator();

    @Test
    void missingWhenNoArtifacts(@TempDir Path tmp) {
        var result = evaluator.evaluate(tmp);

        assertThat(result.status()).isEqualTo(ReadinessStatus.MISSING);
        assertThat(result.recommendedAction()).isEqualTo(RecommendedAction.PREPARE);
        assertThat(result.reasonLines()).isNotEmpty();
        assertThat(result.lastPreparedAt()).isNull();
    }

    @Test
    void partialWhenStandardsSidecarAbsent(@TempDir Path tmp) throws IOException {
        prepare(tmp, LaunchpadVersion.current(), Instant.now());
        Files.delete(tmp.resolve(".launchpad/standards.index.json"));

        var result = evaluator.evaluate(tmp);

        assertThat(result.status()).isEqualTo(ReadinessStatus.PARTIAL);
        assertThat(result.recommendedAction()).isEqualTo(RecommendedAction.REFRESH);
        assertThat(result.reasonLines()).anyMatch(line -> line.contains("standards.index.json"));
    }

    @Test
    void readyWhenAllArtifactsCurrentAndConsistent(@TempDir Path tmp) throws IOException {
        var generatedAt = Instant.now();
        prepare(tmp, LaunchpadVersion.current(), generatedAt);

        var result = evaluator.evaluate(tmp);

        assertThat(result.status()).isEqualTo(ReadinessStatus.READY);
        assertThat(result.recommendedAction()).isEqualTo(RecommendedAction.NONE);
        assertThat(result.reasonLines()).isEmpty();
        assertThat(result.lastPreparedAt()).isEqualTo(generatedAt);
    }

    @Test
    void staleWhenLaunchpadVersionDiffers(@TempDir Path tmp) throws IOException {
        prepare(tmp, "0.0.1-ancient", Instant.now());

        var result = evaluator.evaluate(tmp);

        assertThat(result.status()).isEqualTo(ReadinessStatus.STALE);
        assertThat(result.recommendedAction()).isEqualTo(RecommendedAction.REFRESH);
        assertThat(result.reasonLines()).anyMatch(line -> line.contains("version changed"));
    }

    @Test
    void staleWhenBuildFileNewerThanStamp(@TempDir Path tmp) throws IOException {
        prepare(tmp, LaunchpadVersion.current(), Instant.now().minusSeconds(3600));
        var pom = Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Files.setLastModifiedTime(pom, java.nio.file.attribute.FileTime.from(Instant.now()));

        var result = evaluator.evaluate(tmp);

        assertThat(result.status()).isEqualTo(ReadinessStatus.STALE);
        assertThat(result.reasonLines()).anyMatch(line -> line.contains("pom.xml"));
    }

    @Test
    void staleWhenProvenanceMissingOrGarbled(@TempDir Path tmp) throws IOException {
        prepare(tmp, LaunchpadVersion.current(), Instant.now());
        Files.writeString(tmp.resolve("AGENTS.md"),
            "<!-- launchpad:provenance {not valid json} -->\n# Project\n");

        var result = evaluator.evaluate(tmp);

        assertThat(result.status()).isEqualTo(ReadinessStatus.STALE);
        assertThat(result.recommendedAction()).isEqualTo(RecommendedAction.REFRESH);
        assertThat(result.lastPreparedAt()).isNull();
    }

    @Test
    void errorWhenStandardsSidecarIsInvalidJson(@TempDir Path tmp) throws IOException {
        prepare(tmp, LaunchpadVersion.current(), Instant.now());
        Files.writeString(tmp.resolve(".launchpad/standards.index.json"), "{ this is not json");

        var result = evaluator.evaluate(tmp);

        assertThat(result.status()).isEqualTo(ReadinessStatus.ERROR);
        assertThat(result.recommendedAction()).isEqualTo(RecommendedAction.REFRESH);
        assertThat(result.reasonLines()).anyMatch(line -> line.contains("standards.index.json"));
    }

    @Test
    void statusEnumDefinesAllSevenPaths() {
        assertThat(ReadinessStatus.values()).hasSize(7)
            .contains(ReadinessStatus.UNSUPPORTED, ReadinessStatus.IGNORED);
    }

    @Test
    void evaluateNeverMutatesTheProject(@TempDir Path tmp) throws IOException {
        prepare(tmp, LaunchpadVersion.current(), Instant.now());
        var before = snapshot(tmp);

        evaluator.evaluate(tmp);

        assertThat(snapshot(tmp)).isEqualTo(before);
    }

    /** Writes a fully prepared project: AGENTS.md with a valid stamp plus all three sidecars. */
    private void prepare(Path root, String launchpadVersion, Instant generatedAt) throws IOException {
        Files.createDirectories(root.resolve(".launchpad"));
        var stamp = new ProvenanceHeader(ProvenanceHeader.SCHEMA_VERSION, launchpadVersion,
            generatedAt.toString(), null, ProvenanceHeader.DETERMINISTIC_ONLY);
        Files.writeString(root.resolve("AGENTS.md"), stamp.render() + "# Project\n");
        Files.writeString(root.resolve(".launchpad/project-context.json"), "{}");
        Files.writeString(root.resolve(".launchpad/project.model.json"), "{}");
        Files.writeString(root.resolve(".launchpad/standards.index.json"), "{}");
    }

    /** Relative path -> size + mtime + content hash, for asserting nothing changed. */
    private Map<String, String> snapshot(Path root) throws IOException {
        var snapshot = new HashMap<String, String>();
        try (Stream<Path> files = Files.walk(root).sorted(Comparator.naturalOrder())) {
            for (var path : files.filter(Files::isRegularFile).toList()) {
                var mtime = Files.getLastModifiedTime(path).toMillis();
                var hash = sha256(Files.readAllBytes(path));
                snapshot.put(root.relativize(path).toString(),
                    Files.size(path) + ":" + mtime + ":" + hash);
            }
        }
        return snapshot;
    }

    private static String sha256(byte[] content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
