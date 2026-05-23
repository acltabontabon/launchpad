package com.acltabontabon.launchpad.tui.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Orchestrates per-client config writes for the "Connect to AI tool" flow.
 * Backs up any existing file to {@code ~/.launchpad/backups/<ts>/<client>/<filename>}
 * before merging the MCP snippet under {@code mcpServers.launchpad} and writing
 * atomically. Returns one {@link WriteReport} per processed client so the result
 * screen can render outcomes without re-running the work.
 */
@Component
public class McpConfigWriter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path userHome;

    public McpConfigWriter() {
        this(Path.of(System.getProperty("user.home")));
    }

    McpConfigWriter(Path userHome) {
        this.userHome = userHome;
    }

    public record WriteRun(Path backupDir, List<WriteReport> reports) {}

    public WriteRun apply(List<AiClient> selectedClients, McpSnippet snippet) {
        var ts = LocalDateTime.now().format(TS_FMT);
        var backupRoot = userHome.resolve(".launchpad").resolve("backups").resolve(ts);
        var reports = new ArrayList<WriteReport>(selectedClients.size());
        boolean anyBackup = false;

        for (var client : selectedClients) {
            if (snippet == null) {
                reports.add(new WriteReport(client.id(), WriteReport.Outcome.ERROR_DEV_MODE,
                    "install the native launchpad binary first"));
                continue;
            }
            try {
                anyBackup |= writeOne(client, snippet, backupRoot, reports);
            } catch (IOException e) {
                reports.add(new WriteReport(client.id(), WriteReport.Outcome.ERROR_IO, e.getMessage()));
            }
        }

        return new WriteRun(anyBackup ? backupRoot : null, List.copyOf(reports));
    }

    private boolean writeOne(AiClient client, McpSnippet snippet, Path backupRoot,
                             List<WriteReport> reports) throws IOException {
        var target = client.configPath();
        String existing = Files.isRegularFile(target) ? Files.readString(target) : "";

        String merged;
        try {
            merged = JsonMcpMerger.merge(existing, snippet);
        } catch (JsonMcpMerger.KeyAlreadyPresentException e) {
            reports.add(new WriteReport(client.id(), WriteReport.Outcome.SKIPPED_KEY_EXISTS,
                "already configured; remove it manually to re-link"));
            return false;
        } catch (JsonMcpMerger.McpServersNotObjectException e) {
            reports.add(new WriteReport(client.id(), WriteReport.Outcome.ERROR_NOT_OBJECT,
                e.getMessage() + " - edit " + target + " manually"));
            return false;
        }

        boolean backedUp = false;
        if (Files.isRegularFile(target)) {
            var backupPath = backupRoot.resolve(client.id().slug()).resolve(target.getFileName().toString());
            Files.createDirectories(backupPath.getParent());
            Files.copy(target, backupPath, StandardCopyOption.REPLACE_EXISTING);
            backedUp = true;
        }

        var parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        var tmp = target.resolveSibling(target.getFileName().toString() + ".launchpad.tmp");
        Files.writeString(tmp, merged);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        reports.add(new WriteReport(client.id(), WriteReport.Outcome.WRITTEN, target.toString()));
        return backedUp;
    }
}
