package com.acltabontabon.launchpad.tui.discovery;

import com.acltabontabon.launchpad.config.WorkspaceConfigService;
import com.acltabontabon.launchpad.scanner.ProjectSupportDetector;
import com.acltabontabon.launchpad.scanner.build.BuildSystemDetector;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Walks the user's common dev roots looking for Spring Boot projects (Maven or
 * Gradle) so the project-picker can surface them without the user typing a
 * path. Results are cached for the JVM lifetime - a fresh scan only happens on
 * first request (or via {@link #refresh()}).
 */
@Component
public final class ProjectDiscovery {

    private static final Logger log = LoggerFactory.getLogger(ProjectDiscovery.class);

    /** Directories never worth descending into. */
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "build", "dist", ".idea", ".vscode",
        ".gradle", ".mvn", "out", "bin", ".launchpad", ".cache", "vendor");

    private final ProjectSupportDetector detector;
    private final WorkspaceConfigService workspaceConfig;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "launchpad-discovery");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<List<DiscoveredProject>> results =
        new AtomicReference<>(List.of());
    private final AtomicBoolean inProgress = new AtomicBoolean(false);
    private final AtomicBoolean everRan = new AtomicBoolean(false);

    public ProjectDiscovery(ProjectSupportDetector detector, WorkspaceConfigService workspaceConfig) {
        this.detector = detector;
        this.workspaceConfig = workspaceConfig;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Latest snapshot of discovered projects. Immutable. Empty until the first scan completes. */
    public List<DiscoveredProject> snapshot() {
        return results.get();
    }

    /** True while a background walk is running. Views render a small spinner when true. */
    public boolean isScanning() {
        return inProgress.get();
    }

    /** True after the first walk has completed at least once. */
    public boolean hasRun() {
        return everRan.get();
    }

    /** Kick off a scan if one hasn't started yet. Idempotent and cheap to call every frame. */
    public void triggerOnce() {
        if (everRan.get() || inProgress.get()) return;
        startScan();
    }

    /** Force a re-scan (e.g. after the user moves projects around). */
    public void refresh() {
        if (inProgress.get()) return;
        startScan();
    }

    private void startScan() {
        if (!inProgress.compareAndSet(false, true)) return;
        executor.submit(() -> {
            try {
                var found = walkRoots();
                results.set(found);
            } catch (RuntimeException e) {
                log.warn("Project discovery failed", e);
            } finally {
                inProgress.set(false);
                everRan.set(true);
            }
        });
    }

    private List<DiscoveredProject> walkRoots() {
        var hits = new ArrayList<DiscoveredProject>();
        // Read config once per scan so a settings edit mid-walk can't produce an
        // inconsistent walk; the next refresh() picks up the change.
        var depth = workspaceConfig.depth();
        var detectGitOnly = workspaceConfig.detectGitOnly();
        var ignored = canonicalizeAll(workspaceConfig.ignoredPaths());
        // Dedupe by canonical path so case-insensitive filesystems (macOS default)
        // don't surface "~/Workspace/Foo" and "~/workspace/Foo" as separate hits.
        var seenRoots = new HashSet<Path>();
        for (var root : workspaceConfig.resolvedRoots()) {
            if (!Files.isDirectory(root)) continue;
            var canonical = canonicalize(root);
            if (isIgnored(canonical, ignored)) continue;
            if (!seenRoots.add(canonical)) continue;
            walkRoot(canonical, hits, depth, detectGitOnly, ignored);
        }
        hits.sort(Comparator
            .comparing(DiscoveredProject::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(p -> p.path().toString()));
        return List.copyOf(hits);
    }

    private static Path canonicalize(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static Set<Path> canonicalizeAll(List<Path> paths) {
        var out = new HashSet<Path>();
        for (var p : paths) {
            out.add(canonicalize(p));
        }
        return out;
    }

    /** True when {@code path} is, or sits under, any ignored directory. */
    private static boolean isIgnored(Path path, Set<Path> ignored) {
        return ignored.stream().anyMatch(path::startsWith);
    }

    private void walkRoot(Path root, List<DiscoveredProject> hits, int depth,
                          boolean detectGitOnly, Set<Path> ignored) {
        try {
            Files.walkFileTree(root, Set.of(), depth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    var name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(root) && name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(root) && isIgnored(canonicalize(dir), ignored)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (BuildSystemDetector.isProjectRoot(dir)) {
                        if (detectGitOnly && !Files.exists(dir.resolve(".git"))) {
                            return FileVisitResult.CONTINUE;
                        }
                        var result = detector.detect(dir);
                        if (result.isSupported()) {
                            var canonical = canonicalize(dir);
                            hits.add(new DiscoveredProject(
                                canonical,
                                canonical.getFileName().toString(),
                                result.framework()));
                            // Don't descend into a recognised project's submodules - they'd
                            // show up as duplicates of the parent in the picker.
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("Discovery walk failed under {}", root, e);
        }
    }
}
