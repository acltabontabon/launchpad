package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Persists a {@link ProjectContext} to {@code <projectRoot>/.launchpad/scan.json}
 * so that out-of-process consumers (the MCP server, future tooling) can read
 * what the TUI already scanned without re-walking the tree.
 * <p>
 * The format is the natural Jackson serialization of the record. Stability is
 * provided by {@code @JsonIgnoreProperties(ignoreUnknown = true)} on consumer
 * records when fields evolve; readers should never crash on a newer file.
 */
@Component
public class ScanStore {

    static final String SCAN_DIR = ".launchpad";
    static final String SCAN_FILE = "scan.json";

    private final ObjectMapper json = new ObjectMapper()
        .findAndRegisterModules()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /** Writes the context to {@code .launchpad/scan.json} under the given project root. */
    public Path save(Path projectRoot, ProjectContext context) {
        var target = scanFile(projectRoot);
        try {
            Files.createDirectories(target.getParent());
            json.writeValue(target.toFile(), context);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }

    /** Reads the context if present and parseable; empty otherwise. */
    public Optional<ProjectContext> load(Path projectRoot) {
        var source = scanFile(projectRoot);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(source.toFile(), ProjectContext.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** True when {@code scan.json} exists and is younger than {@code maxAge}. */
    public boolean isFresh(Path projectRoot, Duration maxAge) {
        var source = scanFile(projectRoot);
        if (!Files.isRegularFile(source)) {
            return false;
        }
        try {
            var modified = Files.getLastModifiedTime(source).toInstant();
            return Duration.between(modified, Instant.now()).compareTo(maxAge) < 0;
        } catch (IOException e) {
            return false;
        }
    }

    public Path scanFile(Path projectRoot) {
        return projectRoot.resolve(SCAN_DIR).resolve(SCAN_FILE);
    }
}
