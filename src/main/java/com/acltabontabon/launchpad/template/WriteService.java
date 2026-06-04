package com.acltabontabon.launchpad.template;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Applies a list of {@link FilePlan}s to disk, backing up any file we would
 * change before writing. Backups land under
 * {@code <projectRoot>/.launchpad/backups/<timestamp>/<relpath>}.
 */
@Service
public class WriteService {

    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public record Result(int written, int skipped, int backedUp, Path backupDir, List<String> errors) { }

    public Result apply(Path projectRoot, List<FilePlan> plans) throws IOException {
        var ts = LocalDateTime.now().format(TS_FMT);
        var backupDir = projectRoot.resolve(".launchpad").resolve("backups").resolve(ts);
        var safeRoot = projectRoot.toRealPath();
        var errors = new ArrayList<String>();
        int written = 0;
        int skipped = 0;
        int backedUp = 0;
        boolean backupDirCreated = false;
        Path safeBackupDir = null;

        for (var plan : plans) {
            if (plan.action() == FilePlan.Action.SKIP
                || plan.action() == FilePlan.Action.CORRUPTED
                || plan.action() == FilePlan.Action.UNREADABLE) {
                skipped++;
                continue;
            }
            var rel = plan.file.relativePath();
            var target = safeRoot.resolve(rel).normalize();
            if (!target.startsWith(safeRoot)) {
                errors.add(rel + ": refused, path escapes project root");
                continue;
            }
            try {
                if (plan.exists) {
                    if (!backupDirCreated) {
                        Files.createDirectories(backupDir);
                        safeBackupDir = backupDir.toRealPath();
                        backupDirCreated = true;
                    }
                    var backupPath = safeBackupDir.resolve(rel).normalize();
                    if (!backupPath.startsWith(safeBackupDir)) {
                        errors.add(rel + ": refused, backup path escapes backup root");
                        continue;
                    }
                    Files.createDirectories(backupPath.getParent());
                    Files.copy(target, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    backedUp++;
                }
                var parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(target, plan.resolvedContent());
                written++;
            } catch (IOException | RuntimeException e) {
                errors.add(rel + ": " + e.getMessage());
            }
        }
        return new Result(written, skipped, backedUp,
            backupDirCreated ? backupDir : null, errors);
    }
}
