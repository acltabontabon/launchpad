package com.acltabontabon.launchpad.standards.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Persists a {@link StandardsIndex} to
 * {@code <projectRoot>/.launchpad/standards.index.json} so out-of-process
 * consumers (the MCP server, a context-mode index, downstream tooling) can
 * retrieve a single rule without carrying the whole standards pack.
 * <p>
 * Mirrors {@link com.acltabontabon.launchpad.model.graph.ProjectModelStore}:
 * the format is the natural Jackson serialization of the record, kept
 * forward-compatible by {@code @JsonIgnoreProperties(ignoreUnknown = true)} on
 * the model records so readers never crash on a newer file.
 */
@Component
public class StandardsIndexStore {

    private static final Logger log = LoggerFactory.getLogger(StandardsIndexStore.class);

    static final String STORE_DIR = ".launchpad";
    static final String STORE_FILE = "standards.index.json";

    private final ObjectMapper json = new ObjectMapper()
        .findAndRegisterModules()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /** Writes the index to {@code .launchpad/standards.index.json} under the given project root. */
    public Path save(Path projectRoot, StandardsIndex index) {
        var target = storeFile(projectRoot);
        try {
            Files.createDirectories(target.getParent());
            json.writeValue(target.toFile(), index);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }

    /** Reads the index if present and parseable; empty otherwise. */
    public Optional<StandardsIndex> load(Path projectRoot) {
        var source = storeFile(projectRoot);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(source.toFile(), StandardsIndex.class));
        } catch (IOException | RuntimeException e) {
            // A stale or corrupt index file should never block a re-run, but
            // never fail silently - log loudly so the cause surfaces.
            log.warn("Failed to read standards index at {} - treating as absent.", source, e);
            return Optional.empty();
        }
    }

    public Path storeFile(Path projectRoot) {
        return projectRoot.resolve(STORE_DIR).resolve(STORE_FILE);
    }
}
