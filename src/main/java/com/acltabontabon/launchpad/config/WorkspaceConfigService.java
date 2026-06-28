package com.acltabontabon.launchpad.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * User-global workspace configuration, persisted to {@code ~/.launchpad/config.yml}.
 * The single source of truth for which directories Launchpad scans for projects:
 * the dev roots, an ignore list, the scan depth, and a git-only detection gate.
 * Consumed today by the TUI discovery walk ({@code ProjectDiscovery}) and live
 * search; the UI (#137/#129), CLI (#124), and MCP layers read and write the same
 * service.
 * <p>
 * Schema:
 * <pre>
 * workspace:
 *   roots:           # when present, REPLACES the built-in defaults (even when empty)
 *     - ~/Workspace
 *     - ~/clients
 *   additionalRoots: # appended to defaults only when roots is NOT set
 *     - /work/projects
 *   ignored:
 *     - ~/dev/legacy-vendor-lib
 *   depth: 3          # clamped 1-5, default 3
 *   detectGitOnly: true
 * </pre>
 * {@code ~} and {@code ~/<subpath>} expand to absolute paths on read. An absent
 * file means "use defaults" (no error); a parse failure warns and falls back to
 * defaults, surfaced via {@link #lastLoadError()} so the TUI can show a warning
 * instead of silently behaving as if the file were empty.
 * <p>
 * Thread-safe via copy-on-write through an {@link AtomicReference}; writes are
 * serialized through {@link #mutate}, mirroring {@link ProjectRegistry}.
 */
@Component
public class WorkspaceConfigService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceConfigService.class);

    /**
     * Built-in dev-root names relative to {@code $HOME}. Stored and seeded as
     * {@code ~}-prefixed specs so a config written from defaults stays portable.
     */
    static final List<String> DEFAULT_ROOT_NAMES =
        List.of("Workspace", "workspace", "code", "Code", "dev", "Developer",
                "src", "Projects", "projects", "repos", "git");

    static final int DEFAULT_DEPTH = 3;
    static final int MIN_DEPTH = 1;
    static final int MAX_DEPTH = 5;
    static final boolean DEFAULT_DETECT_GIT_ONLY = true;

    /** Empty (all-defaults) state: roots/additionalRoots null = "use defaults". */
    private static final Workspace EMPTY = new Workspace(null, null, null, null, null);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(Workspace workspace) {
        Document {
            workspace = workspace == null ? EMPTY : workspace;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Workspace(
        List<String> roots,
        List<String> additionalRoots,
        List<String> ignored,
        Integer depth,
        Boolean detectGitOnly
    ) {
        Workspace {
            // Deliberately do NOT coerce roots/additionalRoots: the replace-vs-append
            // rule depends on distinguishing "unset" (null) from "explicitly empty"
            // (an empty list, meaning "no roots at all"). Only ignored is normalized.
            ignored = ignored == null ? List.of() : List.copyOf(ignored);
        }

        Workspace withRoots(List<String> r) {
            return new Workspace(r, additionalRoots, ignored, depth, detectGitOnly);
        }

        Workspace withIgnored(List<String> i) {
            return new Workspace(roots, additionalRoots, i, depth, detectGitOnly);
        }

        Workspace withDepth(Integer d) {
            return new Workspace(roots, additionalRoots, ignored, d, detectGitOnly);
        }

        Workspace withDetectGitOnly(Boolean g) {
            return new Workspace(roots, additionalRoots, ignored, depth, g);
        }
    }

    private final Path configFile;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT);
    private final AtomicReference<Workspace> current;
    private final AtomicReference<String> lastLoadError = new AtomicReference<>();

    public WorkspaceConfigService() {
        this(Path.of(System.getProperty("user.home"), ".launchpad", "config.yml"));
    }

    /** Construct against a custom config path (used by tests and embedders). */
    public WorkspaceConfigService(Path configFile) {
        this.configFile = configFile;
        this.current = new AtomicReference<>(loadFromDisk());
    }

    // ---- Resolved read API (what the scanner consumes) ----------------------

    /**
     * Effective scan roots as absolute, normalized paths. Applies the replace
     * (roots set) / append (only additionalRoots set) rule, expands {@code ~},
     * and de-dupes order-preserving. Path existence is NOT filtered here -
     * callers decide what to do with a configured-but-missing root.
     */
    public List<Path> resolvedRoots() {
        var ws = current.get();
        List<String> effective;
        if (ws.roots() != null) {
            effective = ws.roots();
        } else {
            effective = new ArrayList<>(defaultRootSpecs());
            if (ws.additionalRoots() != null) {
                effective.addAll(ws.additionalRoots());
            }
        }
        return expandDistinct(effective);
    }

    /** Ignored paths as absolute, normalized paths ({@code ~} expanded). */
    public List<Path> ignoredPaths() {
        return expandDistinct(current.get().ignored());
    }

    /** Scan depth, clamped to {@value #MIN_DEPTH}..{@value #MAX_DEPTH} (default {@value #DEFAULT_DEPTH}). */
    public int depth() {
        var d = current.get().depth();
        return clampDepth(d == null ? DEFAULT_DEPTH : d);
    }

    /** Whether discovery should only accept directories that are also git repositories. */
    public boolean detectGitOnly() {
        var g = current.get().detectGitOnly();
        return g == null ? DEFAULT_DETECT_GIT_ONLY : g;
    }

    /**
     * Non-null reason string when the last load failed (file present but
     * unreadable/unparseable), otherwise null. Surfaced by the TUI so a broken
     * config warns instead of silently behaving as defaults.
     */
    public String lastLoadError() {
        return lastLoadError.get();
    }

    // ---- Raw read API (what the settings UI round-trips) --------------------

    /** Raw {@code roots} entries as written (unexpanded), or empty when unset. */
    public List<String> rawRoots() {
        var r = current.get().roots();
        return r == null ? List.of() : r;
    }

    /** Raw {@code additionalRoots} entries as written (unexpanded), or empty when unset. */
    public List<String> rawAdditionalRoots() {
        var a = current.get().additionalRoots();
        return a == null ? List.of() : a;
    }

    /** Raw {@code ignored} entries as written (unexpanded). */
    public List<String> rawIgnored() {
        return current.get().ignored();
    }

    /** True when {@code roots} is set explicitly (replace mode), false when defaults apply (append mode). */
    public boolean rootsExplicitlySet() {
        return current.get().roots() != null;
    }

    /** Raw {@code depth} value as written, or null when unset. */
    public Integer rawDepth() {
        return current.get().depth();
    }

    /** Raw {@code detectGitOnly} value as written, or null when unset. */
    public Boolean rawDetectGitOnly() {
        return current.get().detectGitOnly();
    }

    // ---- Mutators (persist via mutate) --------------------------------------

    /**
     * Add a root. Adding implies explicit/replace mode, so when {@code roots}
     * was unset it is first seeded from the built-in defaults. The raw spec
     * (e.g. {@code ~/clients}) is stored unexpanded for portability.
     */
    public void addRoot(String raw) {
        var v = trim(raw);
        if (v.isBlank()) return;
        mutate(ws -> {
            var roots = ws.roots() == null
                ? new ArrayList<>(defaultRootSpecs())
                : new ArrayList<>(ws.roots());
            if (roots.contains(v)) return ws;
            roots.add(v);
            return ws.withRoots(roots);
        });
    }

    /** Remove a root by raw spec. Returns whether anything was removed. */
    public boolean removeRoot(String raw) {
        var v = trim(raw);
        if (v.isBlank()) return false;
        var ws = current.get();
        var base = ws.roots() == null ? defaultRootSpecs() : ws.roots();
        if (!base.contains(v)) return false;
        var next = new ArrayList<>(base);
        next.remove(v);
        mutate(w -> w.withRoots(next));
        return true;
    }

    /** Add an ignored path (raw spec stored unexpanded). */
    public void addIgnored(String raw) {
        var v = trim(raw);
        if (v.isBlank()) return;
        mutate(ws -> {
            if (ws.ignored().contains(v)) return ws;
            var next = new ArrayList<>(ws.ignored());
            next.add(v);
            return ws.withIgnored(next);
        });
    }

    /** Remove an ignored path by raw spec. Returns whether anything was removed. */
    public boolean removeIgnored(String raw) {
        var v = trim(raw);
        if (v.isBlank()) return false;
        if (!current.get().ignored().contains(v)) return false;
        mutate(ws -> ws.withIgnored(
            ws.ignored().stream().filter(x -> !x.equals(v)).toList()));
        return true;
    }

    /** Set the scan depth (stored clamped to {@value #MIN_DEPTH}..{@value #MAX_DEPTH}). */
    public void setDepth(int depth) {
        var clamped = clampDepth(depth);
        mutate(ws -> ws.withDepth(clamped));
    }

    /** Set the git-only detection gate. */
    public void setDetectGitOnly(boolean detectGitOnly) {
        mutate(ws -> ws.withDetectGitOnly(detectGitOnly));
    }

    // ---- Internals ----------------------------------------------------------

    private static List<String> defaultRootSpecs() {
        return DEFAULT_ROOT_NAMES.stream().map(n -> "~/" + n).toList();
    }

    private static List<Path> expandDistinct(List<String> specs) {
        var out = new LinkedHashSet<Path>();
        for (var spec : specs) {
            if (spec == null || spec.isBlank()) continue;
            out.add(expand(spec));
        }
        return List.copyOf(out);
    }

    /** Expand {@code ~} / {@code ~/<subpath>} against {@code $HOME}; absolutize and normalize. */
    private static Path expand(String raw) {
        var t = raw.trim();
        var home = System.getProperty("user.home");
        Path p;
        if (t.equals("~")) {
            p = Path.of(home);
        } else if (t.startsWith("~/")) {
            p = Path.of(home, t.substring(2));
        } else {
            p = Path.of(t);
        }
        return p.toAbsolutePath().normalize();
    }

    private static int clampDepth(int d) {
        return Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, d));
    }

    private static String trim(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private void mutate(Function<Workspace, Workspace> fn) {
        synchronized (current) {
            var next = fn.apply(current.get());
            current.set(next);
            writeToDisk(next);
        }
    }

    private Workspace loadFromDisk() {
        if (!Files.isRegularFile(configFile)) {
            lastLoadError.set(null);
            return EMPTY;
        }
        try {
            var doc = yaml.readValue(configFile.toFile(), Document.class);
            lastLoadError.set(null);
            return doc.workspace();
        } catch (IOException | RuntimeException e) {
            var summary = e.getClass().getSimpleName() + ": " + e.getMessage();
            lastLoadError.set("Failed to read " + configFile + " - " + summary);
            log.warn("Failed to read workspace config at {} - using defaults. "
                + "If running under GraalVM native, this is usually a missing reflection hint.",
                configFile, e);
            return EMPTY;
        }
    }

    private void writeToDisk(Workspace ws) {
        try {
            Files.createDirectories(configFile.getParent());
            yaml.writeValue(configFile.toFile(), new Document(ws));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + configFile, e);
        }
    }
}
