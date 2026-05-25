package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Resolves rules, skills, checklists, prompts, and adapters from a standards source.
 * <p>
 * For each source directory in priority order [remote-cache, per-project override]:
 *   - If the dir contains {@code standards-pack.yml}, parse the manifest and walk
 *     its includes for the requested kind. First non-empty result wins.
 *   - Else (legacy flat format) read the kind's flat file (rules.yml / skills.yml)
 *     directly from the source dir. Checklists, prompts, and adapters have no
 *     flat-format equivalent - they only resolve in pack mode.
 * <p>
 * Missing include paths inside a manifest are surfaced loudly so tech leads get
 * immediate feedback on typos.
 */
@Component
public class StandardsLoader {

    private static final String OVERRIDE_DIR = ".launchpad/standards";
    private static final String MANIFEST = "standards-pack.yml";
    private static final String RULES_FILENAME = "rules.yml";
    private static final String SKILLS_FILENAME = "skills.yml";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final RemoteStandardsFetcher remoteFetcher;

    public StandardsLoader(RemoteStandardsFetcher remoteFetcher) {
        this.remoteFetcher = remoteFetcher;
    }

    public List<Rule> loadRules(Path projectRoot) {
        return resolveWithFlatFallback(projectRoot,
            dir -> loadPackEntries(dir, inc -> inc.rules(), RulesFile.class, RulesFile::rules),
            dir -> loadFlat(dir.resolve(RULES_FILENAME), RulesFile.class, RulesFile::rules));
    }

    public List<Skill> loadSkills(Path projectRoot) {
        return resolveWithFlatFallback(projectRoot,
            dir -> loadPackEntries(dir, inc -> inc.skills(), SkillsFile.class, SkillsFile::skills),
            dir -> loadFlat(dir.resolve(SKILLS_FILENAME), SkillsFile.class, SkillsFile::skills));
    }

    public List<Checklist> loadChecklists(Path projectRoot) {
        return resolvePackOnly(projectRoot,
            dir -> loadPackEntries(dir, inc -> inc.checklists(), ChecklistsFile.class, ChecklistsFile::checklists));
    }

    public Optional<Adapter> loadAdapter(Path projectRoot, String adapterId) {
        for (Path dir : sourceDirs(projectRoot)) {
            Path manifestFile = dir.resolve(MANIFEST);
            if (!Files.isRegularFile(manifestFile)) continue;
            var manifest = readYaml(manifestFile, StandardsPackManifest.class);
            if (manifest.adapters() == null) continue;
            String relativePath = manifest.adapters().get(adapterId);
            if (relativePath == null) continue;
            Path adapterFile = dir.resolve(relativePath);
            if (!Files.isRegularFile(adapterFile)) {
                throw new UncheckedIOException(
                    "manifest at " + manifestFile + " references missing adapter file " + adapterFile,
                    new IOException("not found"));
            }
            return Optional.of(readYaml(adapterFile, AdapterFile.class).adapter());
        }
        return Optional.empty();
    }

    // === internals ===

    private List<Path> sourceDirs(Path projectRoot) {
        var dirs = new ArrayList<Path>();
        remoteFetcher.ensureCache().ifPresent(dirs::add);
        dirs.add(projectRoot.resolve(OVERRIDE_DIR));
        return dirs;
    }

    private <T> List<T> resolveWithFlatFallback(
        Path projectRoot,
        Function<Path, Optional<List<T>>> packLoader,
        Function<Path, Optional<List<T>>> flatLoader
    ) {
        for (Path dir : sourceDirs(projectRoot)) {
            if (Files.isRegularFile(dir.resolve(MANIFEST))) {
                Optional<List<T>> packResult = packLoader.apply(dir);
                if (packResult.isPresent()) return packResult.get();
            } else {
                Optional<List<T>> flatResult = flatLoader.apply(dir);
                if (flatResult.isPresent()) return flatResult.get();
            }
        }
        return List.of();
    }

    private <T> List<T> resolvePackOnly(
        Path projectRoot,
        Function<Path, Optional<List<T>>> packLoader
    ) {
        for (Path dir : sourceDirs(projectRoot)) {
            if (Files.isRegularFile(dir.resolve(MANIFEST))) {
                Optional<List<T>> packResult = packLoader.apply(dir);
                if (packResult.isPresent()) return packResult.get();
            }
        }
        return List.of();
    }

    private <F, T> Optional<List<T>> loadPackEntries(
        Path packDir,
        Function<StandardsPackManifest.Includes, List<String>> includeSelector,
        Class<F> fileType,
        Function<F, List<T>> entrySelector
    ) {
        Path manifestFile = packDir.resolve(MANIFEST);
        var manifest = readYaml(manifestFile, StandardsPackManifest.class);
        if (manifest.includes() == null) return Optional.empty();
        List<String> includes = includeSelector.apply(manifest.includes());
        if (includes == null || includes.isEmpty()) return Optional.empty();
        var combined = new ArrayList<T>();
        for (String relativePath : includes) {
            Path file = packDir.resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                throw new UncheckedIOException(
                    "manifest at " + manifestFile + " references missing file " + file,
                    new IOException("not found"));
            }
            F fileObj = readYaml(file, fileType);
            List<T> entries = entrySelector.apply(fileObj);
            if (entries != null) combined.addAll(entries);
        }
        return Optional.of(combined);
    }

    private <F, T> Optional<List<T>> loadFlat(
        Path file,
        Class<F> fileType,
        Function<F, List<T>> entrySelector
    ) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        F fileObj = readYaml(file, fileType);
        List<T> entries = entrySelector.apply(fileObj);
        return Optional.of(entries != null ? entries : List.of());
    }

    private <T> T readYaml(Path file, Class<T> type) {
        try (InputStream in = Files.newInputStream(file)) {
            return yaml.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }
}
