package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class StandardsLoader {

    private static final String OVERRIDE_DIR = ".launchpad/standards";
    private static final String RULES_FILENAME = "rules.yml";
    private static final String SKILLS_FILENAME = "skills.yml";
    private static final String BUNDLED_RESOURCE_PREFIX = "standards/";

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public List<Rule> loadRules(Path projectRoot) {
        return load(projectRoot, RULES_FILENAME, RulesFile.class).rules();
    }

    public List<Skill> loadSkills(Path projectRoot) {
        return load(projectRoot, SKILLS_FILENAME, SkillsFile.class).skills();
    }

    private <T> T load(Path projectRoot, String filename, Class<T> type) {
        var override = projectRoot.resolve(OVERRIDE_DIR).resolve(filename);
        if (Files.isRegularFile(override)) {
            try (InputStream in = Files.newInputStream(override)) {
                return yamlMapper.readValue(in, type);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + override, e);
            }
        }
        var bundled = new ClassPathResource(BUNDLED_RESOURCE_PREFIX + filename);
        try (InputStream in = bundled.getInputStream()) {
            return yamlMapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled " + filename, e);
        }
    }
}
