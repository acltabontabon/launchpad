package com.acltabontabon.launchpad.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * User-global registry of projects the user has used Launchpad on.
 * Persists to {@code ~/.launchpad/projects.json}.
 * <p>
 * The registry is the bridge that lets the MCP server address projects by
 * short name across sessions and across AI tools, instead of requiring the
 * user to type absolute paths every time. The TUI enrolls projects as the
 * user reaches the Review screen; the MCP layer reads the registry to
 * resolve names to paths.
 * <p>
 * Thread-safe via copy-on-write through an {@link AtomicReference}: callers
 * see a consistent snapshot and writes are serialized through {@link #mutate}.
 */
@Component
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(List<RegisteredProject> projects) {
        Document {
            projects = projects == null ? List.of() : List.copyOf(projects);
        }
    }

    private final Path registryFile;
    private final ObjectMapper json = new ObjectMapper()
        .registerModule(instantAsIsoModule())
        .enable(SerializationFeature.INDENT_OUTPUT);
    private final AtomicReference<List<RegisteredProject>> current;
    private final AtomicReference<String> lastLoadError = new AtomicReference<>();

    private static SimpleModule instantAsIsoModule() {
        var module = new SimpleModule("InstantAsIso");
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                var text = p.getValueAsString();
                return (text == null || text.isBlank()) ? null : Instant.parse(text);
            }
        });
        return module;
    }

    public ProjectRegistry() {
        this(Path.of(System.getProperty("user.home"), ".launchpad", "projects.json"));
    }

    /** Test-friendly constructor accepting a custom registry path. */
    ProjectRegistry(Path registryFile) {
        this.registryFile = registryFile;
        this.current = new AtomicReference<>(loadFromDisk());
    }

    /**
     * Immutable snapshot of all registered projects, ordered by lastScannedAt desc then name.
     * Each entry is overlaid with its per-project {@code .launchpad/project.yml} metadata
     * (tags, workspace, relatedTo) when present. The overlay is read-only - it never
     * mutates {@code projects.json}.
     */
    public List<RegisteredProject> all() {
        var snapshot = new ArrayList<RegisteredProject>(current.get().size());
        for (var p : current.get()) {
            snapshot.add(overlayMetadata(p));
        }
        snapshot.sort((a, b) -> {
            var aWhen = a.lastScannedAt();
            var bWhen = b.lastScannedAt();
            if (aWhen == null && bWhen == null) return a.name().compareToIgnoreCase(b.name());
            if (aWhen == null) return 1;
            if (bWhen == null) return -1;
            int cmp = bWhen.compareTo(aWhen);
            return cmp != 0 ? cmp : a.name().compareToIgnoreCase(b.name());
        });
        return Collections.unmodifiableList(snapshot);
    }

    private static RegisteredProject overlayMetadata(RegisteredProject base) {
        var overlay = ProjectMetadataFile.load(Path.of(base.path()));
        if (overlay.isEmpty()) return base;
        var meta = overlay.get();
        return base.withMetadata(meta.tags(), meta.workspace(), meta.relatedTo());
    }

    /** Exact name lookup (case-insensitive). */
    public Optional<RegisteredProject> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        var needle = name.trim();
        return current.get().stream()
            .filter(p -> p.name().equalsIgnoreCase(needle))
            .findFirst();
    }

    /** Exact path lookup (canonicalized absolute path). */
    public Optional<RegisteredProject> findByPath(Path path) {
        if (path == null) return Optional.empty();
        var needle = path.toAbsolutePath().normalize().toString();
        return current.get().stream()
            .filter(p -> p.path().equals(needle))
            .findFirst();
    }

    /**
     * Resolve a "project ref" - either a name from the registry or an absolute path -
     * to a concrete path. Used by MCP tools to accept either form.
     */
    public Optional<Path> resolveToPath(String nameOrPath) {
        if (nameOrPath == null || nameOrPath.isBlank()) return Optional.empty();
        var trimmed = nameOrPath.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("~")) {
            var expanded = trimmed.startsWith("~")
                ? Path.of(System.getProperty("user.home"), trimmed.substring(1).replaceFirst("^/", ""))
                : Path.of(trimmed);
            return Optional.of(expanded.toAbsolutePath().normalize());
        }
        return findByName(trimmed).map(p -> Path.of(p.path()));
    }

    /**
     * Register a project (or update an existing entry at the same path).
     * Name is derived from the path's final segment when not provided; collisions
     * get a numeric suffix.
     */
    public RegisteredProject register(Path projectPath, String stack) {
        var canonical = projectPath.toAbsolutePath().normalize();
        var canonicalStr = canonical.toString();
        return mutate(projects -> {
            var existing = projects.stream()
                .filter(p -> p.path().equals(canonicalStr))
                .findFirst();
            var now = Instant.now();
            if (existing.isPresent()) {
                var updated = existing.get()
                    .withLastScannedAt(now)
                    .withStack(stack == null ? existing.get().stack() : stack);
                var out = new ArrayList<>(projects);
                out.set(out.indexOf(existing.get()), updated);
                return out;
            }
            var name = uniqueNameFor(canonical, projects);
            var entry = new RegisteredProject(name, canonicalStr, stack, now, now);
            var out = new ArrayList<>(projects);
            out.add(entry);
            return out;
        }).stream()
            .filter(p -> p.path().equals(canonicalStr))
            .findFirst()
            .orElseThrow();
    }

    /** Remove a project by name. Returns whether anything was removed. */
    public boolean remove(String name) {
        var before = current.get().size();
        mutate(projects -> projects.stream()
            .filter(p -> !p.name().equalsIgnoreCase(name))
            .toList());
        return current.get().size() < before;
    }

    /**
     * Drop entries whose path no longer exists on disk. Returns the names removed.
     * Best run on startup or via the TUI Projects screen.
     */
    public List<String> pruneMissing() {
        var removed = new ArrayList<String>();
        mutate(projects -> {
            var kept = new ArrayList<RegisteredProject>(projects.size());
            for (var p : projects) {
                if (Files.isDirectory(Path.of(p.path()))) {
                    kept.add(p);
                } else {
                    removed.add(p.name());
                }
            }
            return kept;
        });
        return removed;
    }

    private List<RegisteredProject> mutate(java.util.function.Function<List<RegisteredProject>, List<RegisteredProject>> fn) {
        synchronized (current) {
            var next = List.copyOf(fn.apply(current.get()));
            current.set(next);
            writeToDisk(next);
            return next;
        }
    }

    private List<RegisteredProject> loadFromDisk() {
        if (!Files.isRegularFile(registryFile)) {
            return List.of();
        }
        try {
            var doc = json.readValue(registryFile.toFile(), Document.class);
            lastLoadError.set(null);
            return doc.projects();
        } catch (IOException | RuntimeException e) {
            // Surface this loudly. Silently returning an empty list here once
            // made a missing native-image reflection hint look like "no projects
            // ever scanned" - hours of confusion. The TUI Projects screen reads
            // lastLoadError() to render a warning so the failure is never invisible.
            var summary = e.getClass().getSimpleName() + ": " + e.getMessage();
            lastLoadError.set("Failed to read " + registryFile + " - " + summary);
            log.warn("Failed to read project registry at {} - returning empty list. "
                + "If running under GraalVM native, this is usually a missing reflection hint.",
                registryFile, e);
            return List.of();
        }
    }

    /**
     * Non-null reason string if the last {@link #loadFromDisk()} call failed,
     * otherwise null. Consumed by the TUI Projects screen so a corrupted or
     * unreadable registry surfaces a warning instead of a misleading
     * "no projects yet" empty state.
     */
    public String lastLoadError() {
        return lastLoadError.get();
    }

    private void writeToDisk(List<RegisteredProject> projects) {
        try {
            Files.createDirectories(registryFile.getParent());
            json.writeValue(registryFile.toFile(), new Document(projects));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + registryFile, e);
        }
    }

    private static String uniqueNameFor(Path projectPath, List<RegisteredProject> existing) {
        var base = sanitizeName(projectPath.getFileName().toString());
        var taken = existing.stream()
            .map(p -> p.name().toLowerCase(Locale.ROOT))
            .toList();
        if (!taken.contains(base.toLowerCase(Locale.ROOT))) {
            return base;
        }
        int n = 2;
        while (taken.contains((base + "-" + n).toLowerCase(Locale.ROOT))) {
            n++;
        }
        return base + "-" + n;
    }

    private static String sanitizeName(String raw) {
        var trimmed = Objects.requireNonNullElse(raw, "project").trim();
        if (trimmed.isEmpty()) return "project";
        return trimmed.replaceAll("[\\s/\\\\]+", "-");
    }
}
