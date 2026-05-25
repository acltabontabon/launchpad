package com.acltabontabon.launchpad.tui.discovery;

import com.acltabontabon.launchpad.scanner.ProjectSupportDetector;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Debounced, cancellable filesystem search for Spring Boot Maven projects whose
 * directory name contains the user's query. Complements the eager
 * {@link ProjectDiscovery} walk: that one preloads common dev roots at startup;
 * this one fires on demand when the user types something that doesn't already
 * match, walking deeper into {@code $HOME} so projects living in unusual
 * locations are still findable without typing a full path.
 * <p>
 * One walk runs at a time. A new {@link #submit(String)} cancels the prior
 * debounce timer and (if a walk is already running) interrupts it via the
 * generation counter, which {@code preVisitDirectory} checks between
 * directories. Stale results are dropped on completion.
 */
@Component
public final class LiveProjectSearch {

    private static final Logger log = LoggerFactory.getLogger(LiveProjectSearch.class);

    /** Below this many chars, walking $HOME wastes more I/O than it ever pays back. */
    private static final int MIN_QUERY_LEN = 2;
    /** Wait this long after the last keystroke before firing a walk - typing fast skips the work entirely. */
    private static final int DEBOUNCE_MS = 300;
    /** Cap the walk so a pathological homedir (huge node_modules trees that slipped the skip filter) can't hang the picker. */
    private static final int MAX_DEPTH = 6;
    /** Stop early once we have a usable shortlist - the picker can only show so many anyway. */
    private static final int MAX_HITS = 50;

    /** Same skip set as {@link ProjectDiscovery}; matters more here because the search reaches further. */
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "build", "dist", ".idea", ".vscode",
        ".gradle", ".mvn", "out", "bin", ".launchpad", ".cache", "vendor",
        "Library", "Applications", ".Trash", ".npm", ".m2", ".nvm", ".pyenv",
        ".rustup", ".cargo", ".sdkman", "Pictures", "Movies", "Music", "Downloads");

    private final ProjectSupportDetector detector;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "launchpad-search-debounce");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "launchpad-search-worker");
        t.setDaemon(true);
        return t;
    });

    /** Bumped on every submit; checked mid-walk so stale walks bail fast. */
    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicReference<String> resultsQuery = new AtomicReference<>("");
    private final AtomicReference<List<DiscoveredProject>> results = new AtomicReference<>(List.of());
    private final AtomicBoolean searching = new AtomicBoolean(false);

    private volatile String activeQuery = "";
    private volatile ScheduledFuture<?> pendingDebounce;
    private volatile Future<?> currentWalk;

    public LiveProjectSearch(ProjectSupportDetector detector) {
        this.detector = detector;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    /** True while a walk is actually executing (post-debounce). Views render a spinner when true. */
    public boolean isSearching() {
        return searching.get();
    }

    /** The query string the current {@link #results()} matches. May lag {@code activeQuery} while a walk is in flight. */
    public String resultsQuery() {
        return resultsQuery.get();
    }

    public List<DiscoveredProject> results() {
        return results.get();
    }

    /**
     * Submit a query. Below {@link #MIN_QUERY_LEN} chars the current results are
     * cleared and no walk is scheduled. Identical-to-current queries are a no-op.
     * Otherwise: cancel the pending debounce timer (and any running walk via the
     * generation bump), then schedule a fresh walk after {@link #DEBOUNCE_MS}.
     */
    public void submit(String query) {
        var q = query == null ? "" : query.trim();
        if (q.equals(activeQuery)) return;
        activeQuery = q;
        cancelInFlight();
        if (q.length() < MIN_QUERY_LEN) {
            results.set(List.of());
            resultsQuery.set("");
            return;
        }
        long gen = generation.incrementAndGet();
        pendingDebounce = scheduler.schedule(() -> startWalk(q, gen), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** Clear all state - used when the user leaves the picker or switches to path-mode input. */
    public void cancel() {
        activeQuery = "";
        cancelInFlight();
        results.set(List.of());
        resultsQuery.set("");
    }

    private void cancelInFlight() {
        // Bumping the generation makes any running walk bail at the next directory boundary.
        generation.incrementAndGet();
        var d = pendingDebounce;
        if (d != null) d.cancel(false);
        var w = currentWalk;
        if (w != null) w.cancel(true);
    }

    private void startWalk(String query, long gen) {
        currentWalk = worker.submit(() -> {
            if (gen != generation.get()) return;
            searching.set(true);
            try {
                var hits = walkHome(query, gen);
                if (gen == generation.get()) {
                    results.set(List.copyOf(hits));
                    resultsQuery.set(query);
                }
            } catch (RuntimeException e) {
                log.debug("Live search failed for query '{}'", query, e);
            } finally {
                searching.set(false);
            }
        });
    }

    private List<DiscoveredProject> walkHome(String query, long gen) {
        var needle = query.toLowerCase(Locale.ROOT);
        var home = Path.of(System.getProperty("user.home"));
        if (!Files.isDirectory(home)) return List.of();
        var hits = new ArrayList<DiscoveredProject>();
        try {
            Files.walkFileTree(home, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (gen != generation.get() || Thread.currentThread().isInterrupted()) {
                        return FileVisitResult.TERMINATE;
                    }
                    var name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(home) && (SKIP_DIRS.contains(name) || name.startsWith("."))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // Match the directory name first - cheaper than parsing every pom.xml we pass.
                    if (Files.isRegularFile(dir.resolve("pom.xml"))
                        && name.toLowerCase(Locale.ROOT).contains(needle)) {
                        var result = detector.detect(dir);
                        if (result.isSupported()) {
                            var canonical = canonicalize(dir);
                            hits.add(new DiscoveredProject(
                                canonical, canonical.getFileName().toString(), result.framework()));
                            if (hits.size() >= MAX_HITS) return FileVisitResult.TERMINATE;
                            // Don't descend into a recognised project's submodules.
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
            log.debug("Live search walk under {} failed", home, e);
        }
        return hits;
    }

    private static Path canonicalize(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
