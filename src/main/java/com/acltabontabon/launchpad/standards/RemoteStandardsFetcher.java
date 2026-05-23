package com.acltabontabon.launchpad.standards;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private static final int MAX_CAPTURED_OUTPUT_BYTES = 64 * 1024;
    private static final Duration DRAINER_JOIN_TIMEOUT = Duration.ofSeconds(2);

    private static void runGit(Path cwd, String... cmd) throws IOException, InterruptedException {
        var verb = cmd.length > 1 && "-C".equals(cmd[1]) && cmd.length > 3 ? cmd[3] : (cmd.length > 1 ? cmd[1] : "command");
        var process = new ProcessBuilder(cmd)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start();
        var captured = new ByteArrayOutputStream();
        var drainer = new Thread(() -> drain(process.getInputStream(), captured), "launchpad-git-drainer");
        drainer.setDaemon(true);
        drainer.start();

        if (!process.waitFor(GIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroy();
            if (!process.waitFor(DRAINER_JOIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            drainer.join(DRAINER_JOIN_TIMEOUT.toMillis());
            throw new IOException("git " + verb + " exceeded " + GIT_TIMEOUT.toSeconds() + "s"
                + tail(captured));
        }

        drainer.join(DRAINER_JOIN_TIMEOUT.toMillis());
        if (process.exitValue() != 0) {
            throw new IOException("git " + verb + " exited " + process.exitValue() + tail(captured));
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream sink) {
        var buf = new byte[4096];
        try (in) {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (sink.size() < MAX_CAPTURED_OUTPUT_BYTES) {
                    sink.write(buf, 0, Math.min(n, MAX_CAPTURED_OUTPUT_BYTES - sink.size()));
                }
            }
        } catch (IOException ignored) {
            // process killed or stream closed; whatever we captured is what we report
        }
    }

    private static String tail(ByteArrayOutputStream captured) {
        var out = captured.toString(StandardCharsets.UTF_8).trim();
        return out.isEmpty() ? "" : ": " + out;
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
