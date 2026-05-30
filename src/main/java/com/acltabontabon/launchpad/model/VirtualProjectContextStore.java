package com.acltabontabon.launchpad.model;

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
 * Persists a {@link VirtualProjectContext} to
 * {@code <projectRoot>/.launchpad/project-context.json} so out-of-process
 * consumers (the MCP server, downstream tooling, a context-mode index) can
 * read the synthesized project model without re-deriving it.
 * <p>
 * Mirrors {@link com.acltabontabon.launchpad.scanner.ScanStore}: the format is
 * the natural Jackson serialization of the record, kept forward-compatible by
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} on the model records so
 * readers never crash on a newer file.
 */
@Component
public class VirtualProjectContextStore {

    private static final Logger log = LoggerFactory.getLogger(VirtualProjectContextStore.class);

    static final String STORE_DIR = ".launchpad";
    static final String STORE_FILE = "project-context.json";

    private final ObjectMapper json = new ObjectMapper()
        .findAndRegisterModules()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /** Writes the model to {@code .launchpad/project-context.json} under the given project root. */
    public Path save(Path projectRoot, VirtualProjectContext model) {
        var target = storeFile(projectRoot);
        try {
            Files.createDirectories(target.getParent());
            json.writeValue(target.toFile(), model);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }

    /** Reads the model if present and parseable; empty otherwise. */
    public Optional<VirtualProjectContext> load(Path projectRoot) {
        var source = storeFile(projectRoot);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(source.toFile(), VirtualProjectContext.class));
        } catch (IOException | RuntimeException e) {
            // A stale or corrupt model file should never block a re-run, but
            // never fail silently - log loudly so the cause surfaces.
            log.warn("Failed to read project model at {} - treating as absent.", source, e);
            return Optional.empty();
        }
    }

    public Path storeFile(Path projectRoot) {
        return projectRoot.resolve(STORE_DIR).resolve(STORE_FILE);
    }
}
