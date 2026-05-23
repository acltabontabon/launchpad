package com.acltabontabon.launchpad.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional per-project relationship metadata, loaded from
 * {@code <projectRoot>/.launchpad/project.yml}. Source of truth for
 * {@link RegisteredProject#tags()}, {@link RegisteredProject#workspace()},
 * and {@link RegisteredProject#relatedTo()}; overlaid by {@link ProjectRegistry}
 * on every read.
 * <p>
 * Schema:
 * <pre>
 * tags: [backend, payments]
 * workspace: shop
 * relatedTo: [shop-frontend, shop-api]
 * </pre>
 * All three fields are optional; missing values overlay as empty / null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectMetadataFile(
    List<String> tags,
    String workspace,
    List<String> relatedTo
) {

    private static final Logger log = LoggerFactory.getLogger(ProjectMetadataFile.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public ProjectMetadataFile {
        tags = tags == null ? List.of() : List.copyOf(tags);
        relatedTo = relatedTo == null ? List.of() : List.copyOf(relatedTo);
    }

    /**
     * Load the metadata file for a project. Returns empty if the file is
     * absent or fails to parse - a broken project.yml must not block registry
     * reads. Parse failures are logged once at WARN so the user sees the
     * mistake without crashing the MCP server.
     */
    public static Optional<ProjectMetadataFile> load(Path projectRoot) {
        var file = projectRoot.resolve(".launchpad").resolve("project.yml");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.ofNullable(YAML.readValue(file.toFile(), ProjectMetadataFile.class));
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read {} - ignoring overlay: {}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
