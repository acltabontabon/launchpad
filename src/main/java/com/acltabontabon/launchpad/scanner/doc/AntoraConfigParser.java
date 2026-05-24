package com.acltabontabon.launchpad.scanner.doc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the two fields Launchpad cares about from an Antora {@code antora.yml}
 * descriptor: {@code name} (the component name) and {@code title} (the human
 * label). Antora's full component-version model is out of scope - we only need
 * enough to label the project's docs index.
 */
final class AntoraConfigParser {

    private static final Logger log = LoggerFactory.getLogger(AntoraConfigParser.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    record AntoraConfig(String name, String title) {}

    static Optional<AntoraConfig> load(Path antoraYml) {
        if (!Files.isRegularFile(antoraYml)) return Optional.empty();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = YAML.readValue(antoraYml.toFile(), Map.class);
            if (root == null) return Optional.empty();
            String name = asString(root.get("name"));
            String title = asString(root.get("title"));
            return Optional.of(new AntoraConfig(name, title));
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read {} - ignoring Antora config: {}", antoraYml, e.getMessage());
            return Optional.empty();
        }
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private AntoraConfigParser() {}
}
