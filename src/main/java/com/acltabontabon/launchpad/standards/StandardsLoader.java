package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class StandardsLoader {

    private static final String OVERRIDE_DIR = ".launchpad/standards";
    private static final String RULES_FILENAME = "rules.yml";
    private static final String SKILLS_FILENAME = "skills.yml";

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final RemoteStandardsFetcher remoteFetcher;

    public StandardsLoader(RemoteStandardsFetcher remoteFetcher) {
        this.remoteFetcher = remoteFetcher;
    }

    public List<Rule> loadRules(Path projectRoot) {
        return load(projectRoot, RULES_FILENAME, RulesFile.class)
            .map(RulesFile::rules)
            .orElseGet(List::of);
    }

    public List<Skill> loadSkills(Path projectRoot) {
        return load(projectRoot, SKILLS_FILENAME, SkillsFile.class)
            .map(SkillsFile::skills)
            .orElseGet(List::of);
    }

    /**
     * Resolution order (full-file-replace, per file):
     *   1. Remote git cache (if configured + file exists in cache)
     *   2. Per-project override at .launchpad/standards/
     *   3. Empty - no bundled defaults ship with Launchpad
     */
    private <T> Optional<T> load(Path projectRoot, String filename, Class<T> type) {
        var remote = remoteFetcher.ensureCache().map(dir -> dir.resolve(filename));
        if (remote.isPresent() && Files.isRegularFile(remote.get())) {
            return Optional.of(readYaml(remote.get(), type));
        }
        var override = projectRoot.resolve(OVERRIDE_DIR).resolve(filename);
        if (Files.isRegularFile(override)) {
            return Optional.of(readYaml(override, type));
        }
        return Optional.empty();
    }

    private <T> T readYaml(Path file, Class<T> type) {
        try (InputStream in = Files.newInputStream(file)) {
            return yamlMapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }
}
