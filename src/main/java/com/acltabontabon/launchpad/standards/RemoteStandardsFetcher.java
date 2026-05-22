package com.acltabontabon.launchpad.standards;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Clones / pulls the central rules + skills git repo into a local cache.
 * Used by StandardsLoader (TTL-guarded, called per scan) and
 * RemoteStandardsChecker (forced refresh, called for the Welcome badge).
 */
@Component
public class RemoteStandardsFetcher {

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private final LaunchpadSettings settings;
    private final Path cacheRoot =
        Path.of(System.getProperty("user.home"), ".launchpad", "standards-cache");
    private final AtomicReference<RemoteStandardsStatus> lastStatus =
        new AtomicReference<>(RemoteStandardsStatus.checking());

    public RemoteStandardsFetcher(LaunchpadSettings settings) {
        this.settings = settings;
    }

    /**
     * Returns the local directory containing the cached remote standards files,
     * or empty if no remote is configured or no usable cache exists.
     * Honours CACHE_TTL - skips git pull if the cache was refreshed recently.
     */
    public Optional<Path> ensureCache() {
        var snap = settings.snapshot();
        if (!snap.hasRemoteStandards()) {
            return Optional.empty();
        }
        var cacheDir = cacheDirFor(snap.remoteStandardsUrl());
        if (hasGitDir(cacheDir) && isFresh(cacheDir)) {
            return Optional.of(cacheDir);
        }
        return doFetch(snap.remoteStandardsUrl(), cacheDir);
    }

    /**
     * Forces a refresh regardless of TTL. Updates lastStatus.
     * Returns the cache dir if usable (fresh or stale), empty otherwise.
     */
    public Optional<Path> refreshNow() {
        var snap = settings.snapshot();
        if (!snap.hasRemoteStandards()) {
            lastStatus.set(RemoteStandardsStatus.notConfigured());
            return Optional.empty();
        }
        return doFetch(snap.remoteStandardsUrl(), cacheDirFor(snap.remoteStandardsUrl()));
    }

    public RemoteStandardsStatus lastStatus() {
        return lastStatus.get();
    }

    private Optional<Path> doFetch(String url, Path cacheDir) {
        var hasCache = hasGitDir(cacheDir);
        try {
            if (hasCache) {
                runGit(cacheDir, "git", "-C", cacheDir.toString(), "pull", "--ff-only");
            } else {
                Files.createDirectories(cacheRoot);
                runGit(cacheRoot, "git", "clone", "--depth", "1", url, cacheDir.toString());
            }
            touch(cacheDir);
            lastStatus.set(RemoteStandardsStatus.synced(Instant.now()));
            return Optional.of(cacheDir);
        } catch (Exception e) {
            if (hasCache) {
                lastStatus.set(RemoteStandardsStatus.staleCache(e.getMessage()));
                return Optional.of(cacheDir);
            }
            lastStatus.set(RemoteStandardsStatus.error(e.getMessage()));
            return Optional.empty();
        }
    }

    private Path cacheDirFor(String url) {
        return cacheRoot.resolve(hash(url));
    }

    private static boolean hasGitDir(Path cacheDir) {
        return Files.isDirectory(cacheDir.resolve(".git"));
    }

    private static boolean isFresh(Path cacheDir) {
        try {
            var mtime = Files.getLastModifiedTime(cacheDir).toInstant();
            return mtime.isAfter(Instant.now().minus(CACHE_TTL));
        } catch (IOException e) {
            return false;
        }
    }

    private static void touch(Path p) {
        try {
            Files.setLastModifiedTime(p, FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // best-effort; falling back to the natural mtime is fine
        }
    }

    private static void runGit(Path cwd, String... cmd) throws IOException, InterruptedException {
        var process = new ProcessBuilder(cmd)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start();
        if (!process.waitFor(GIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git timed out after " + GIT_TIMEOUT.toSeconds() + "s");
        }
        if (process.exitValue() != 0) {
            var output = new String(process.getInputStream().readAllBytes()).trim();
            throw new IOException("git exited " + process.exitValue()
                + (output.isEmpty() ? "" : ": " + output));
        }
    }

    private static String hash(String url) {
        try {
            var digest = MessageDigest.getInstance("SHA-1")
                .digest(url.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(url.hashCode());
        }
    }
}
