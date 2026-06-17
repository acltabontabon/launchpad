package com.acltabontabon.launchpad.standards;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.standards.index.StandardsSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves rules, skills, checklists, prompts, and adapters from a standards source.
 * <p>
 * For each source directory in priority order [remote-cache, per-project override]:
 * a directory is a resolvable pack only when it contains a {@code standards-pack.yml}
 * manifest; the loader parses the manifest and walks its includes for the requested
 * kind, and the first non-empty result wins. A directory without a manifest
 * contributes nothing.
 * <p>
 * Every manifest is read through {@link #readManifest} so its {@code schemaVersion}
 * is validated before any of its contents are used; an unsupported version is
 * rejected with {@link IncompatiblePackSchemaException} rather than loaded silently.
 * Missing include paths inside a manifest are surfaced loudly so tech leads get
 * immediate feedback on typos.
 */
@Component
public class StandardsLoader {

    private static final Logger log = LoggerFactory.getLogger(StandardsLoader.class);
    private static final String OVERRIDE_DIR = ".launchpad/standards";
    private static final String MANIFEST = "standards-pack.yml";
    private static final String DEFAULT_PROJECTION_ID = "claude";

    /** Lowest manifest {@code schemaVersion} this Launchpad can read. */
    static final int SCHEMA_VERSION_MIN_SUPPORTED = 1;
    /** Highest (current) manifest {@code schemaVersion}; bump when the format changes. */
    static final int SCHEMA_VERSION_MAX_SUPPORTED = 1;

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final RemoteStandardsFetcher remoteFetcher;
    @Nullable
    private final LaunchpadSettings settings;

    public StandardsLoader(RemoteStandardsFetcher remoteFetcher,
                           @Nullable LaunchpadSettings settings) {
        this.remoteFetcher = remoteFetcher;
        this.settings = settings;
    }

    /**
     * Resolves the full standards pack - rules, skills, and checklists - plus the
     * pack-level {@code source} in one call, with every record assigned a stable,
     * unique id (see {@link StandardsIdentity}). The sidecar and the audit pass
     * both consume this, so they agree on record identity and content hashes.
     */
    public ResolvedStandards loadResolvedStandards(Path projectRoot) {
        RuleResolution rules = resolveRules(projectRoot);
        return new ResolvedStandards(
            StandardsIdentity.normalizeRules(rules.rules()),
            loadSkills(projectRoot),
            loadChecklists(projectRoot),
            rules.source());
    }

    public List<Rule> loadRules(Path projectRoot) {
        return StandardsIdentity.normalizeRules(resolveRules(projectRoot).rules());
    }

    /**
     * Reports where {@link #loadRules} resolved its rules from. A thin wrapper over
     * {@link #resolveRules} - never an independent resolution path - so the source
     * can never disagree with the rules.
     */
    public Optional<StandardsSource> describeRulesSource(Path projectRoot) {
        return Optional.ofNullable(resolveRules(projectRoot).source());
    }

    public List<Skill> loadSkills(Path projectRoot) {
        return StandardsIdentity.normalizeSkills(resolvePackOnly(projectRoot,
            dir -> loadPackEntries(dir, inc -> inc.skills(), SkillsFile.class, SkillsFile::skills)));
    }

    public List<Checklist> loadChecklists(Path projectRoot) {
        return StandardsIdentity.normalizeChecklists(resolvePackOnly(projectRoot,
            dir -> loadPackEntries(dir, inc -> inc.checklists(), ChecklistsFile.class, ChecklistsFile::checklists)));
    }

    /**
     * Resolves rules and their provenance in a single decision, so the returned
     * {@code source} always describes exactly the returned {@code rules}. The
     * canonical rule resolution; {@link #loadRules}, {@link #describeRulesSource},
     * and {@link #loadResolvedStandards} all wrap it and must never resolve
     * independently. Ids are normalized by the callers via {@link StandardsIdentity}.
     */
    private RuleResolution resolveRules(Path projectRoot) {
        for (LabeledDir labeled : labeledSourceDirs(projectRoot)) {
            Path dir = labeled.dir();
            if (!Files.isRegularFile(dir.resolve(MANIFEST))) continue;
            Optional<List<Rule>> resolved =
                loadPackEntries(dir, inc -> inc.rules(), RulesFile.class, RulesFile::rules);
            if (resolved.isPresent()) {
                return new RuleResolution(resolved.get(), describeSource(labeled));
            }
        }
        return new RuleResolution(List.of(), null);
    }

    /** Rules paired with the source they resolved from, before id normalization. */
    private record RuleResolution(List<Rule> rules, StandardsSource source) {}

    /**
     * Resolves which {@code AgentProjection} ids are enabled for this project,
     * applying this precedence:
     * <ol>
     *   <li>The developer's persisted user preference
     *       ({@code launchpad.projections} in {@code ~/.launchpad/config.properties}),
     *       picked via the TUI projection picker.</li>
     *   <li>The standards-pack manifest's {@code projections:} field, if set.</li>
     *   <li>The back-compat default {@code Set.of("claude")}.</li>
     * </ol>
     *
     * <p>Emission targets are a developer concern (which AI tools they use
     * locally) - the user preference wins over a tech-lead-authored
     * manifest. An empty user preference set means "no projections, just
     * AGENTS.md + .ai/*". Parse failures on the manifest log a WARN and
     * fall back to the default.
     */
    public Set<String> loadProjectionIds(Path projectRoot) {
        if (settings != null) {
            var userPref = settings.snapshot().projections();
            if (userPref != null) return userPref;
        }
        return loadManifestProjectionIds(projectRoot)
            .orElse(Set.of(DEFAULT_PROJECTION_ID));
    }

    private Optional<Set<String>> loadManifestProjectionIds(Path projectRoot) {
        for (Path dir : sourceDirs(projectRoot)) {
            Path manifestFile = dir.resolve(MANIFEST);
            if (!Files.isRegularFile(manifestFile)) continue;
            try {
                var manifest = readManifest(manifestFile);
                if (manifest.projections() == null) return Optional.empty();
                return Optional.of(new LinkedHashSet<>(manifest.projections()));
            } catch (IncompatiblePackSchemaException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to parse 'projections' from {}; using default {}: {}",
                    manifestFile, Set.of(DEFAULT_PROJECTION_ID), e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<Adapter> loadAdapter(Path projectRoot, String adapterId) {
        for (Path dir : sourceDirs(projectRoot)) {
            Path manifestFile = dir.resolve(MANIFEST);
            if (!Files.isRegularFile(manifestFile)) continue;
            var manifest = readManifest(manifestFile);
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

    /** A source directory paired with its origin label, in precedence order. */
    private record LabeledDir(String origin, Path dir) {}

    /**
     * Source directories in precedence order, each tagged with its origin. The
     * single home for standards-source precedence: remote cache (when configured)
     * wins over the per-project override. {@link #sourceDirs} derives from this so
     * every loader sees the same order.
     */
    private List<LabeledDir> labeledSourceDirs(Path projectRoot) {
        var dirs = new ArrayList<LabeledDir>();
        remoteFetcher.ensureCache()
            .ifPresent(cache -> dirs.add(new LabeledDir(StandardsSource.ORIGIN_REMOTE_CACHE, cache)));
        dirs.add(new LabeledDir(StandardsSource.ORIGIN_LOCAL_OVERRIDE, projectRoot.resolve(OVERRIDE_DIR)));
        return dirs;
    }

    private List<Path> sourceDirs(Path projectRoot) {
        return labeledSourceDirs(projectRoot).stream().map(LabeledDir::dir).toList();
    }

    /**
     * Builds the {@link StandardsSource} for a winning directory: its origin plus the
     * manifest's {@code id}/{@code version}. Only ever called for a directory that just
     * resolved entries through {@link #loadPackEntries}, so the manifest is present and
     * its {@code schemaVersion} already validated; re-reading here cannot disagree.
     */
    private StandardsSource describeSource(LabeledDir labeled) {
        var manifest = readManifest(labeled.dir().resolve(MANIFEST));
        return new StandardsSource(manifest.id(), manifest.version(), labeled.origin());
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
        var manifest = readManifest(manifestFile);
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

    /**
     * Reads a manifest and validates its {@code schemaVersion} before returning it.
     * The single chokepoint every manifest read goes through, so an unsupported pack
     * format is rejected uniformly - from the rules path, the projection path, and the
     * adapter path alike - rather than depending on which loader happened to run first.
     *
     * @throws IncompatiblePackSchemaException if {@code schemaVersion} is missing or
     *     outside {@code [SCHEMA_VERSION_MIN_SUPPORTED, SCHEMA_VERSION_MAX_SUPPORTED]}
     */
    private StandardsPackManifest readManifest(Path manifestFile) {
        var manifest = readYaml(manifestFile, StandardsPackManifest.class);
        Integer schemaVersion = manifest.schemaVersion();
        if (schemaVersion == null
            || schemaVersion < SCHEMA_VERSION_MIN_SUPPORTED
            || schemaVersion > SCHEMA_VERSION_MAX_SUPPORTED) {
            throw new IncompatiblePackSchemaException(manifestFile, schemaVersion,
                SCHEMA_VERSION_MIN_SUPPORTED, SCHEMA_VERSION_MAX_SUPPORTED);
        }
        return manifest;
    }

    private <T> T readYaml(Path file, Class<T> type) {
        try (InputStream in = Files.newInputStream(file)) {
            return yaml.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }
}
