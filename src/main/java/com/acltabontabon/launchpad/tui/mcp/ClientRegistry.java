package com.acltabontabon.launchpad.tui.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Resolves per-client MCP config file paths from the user's OS and home
 * directory. "Detected" means the parent dir exists for path-based rows;
 * GENERIC is always shown. Constructor accepts injectable suppliers so tests
 * can run against a temp home without touching the real filesystem.
 */
@Component
public class ClientRegistry {

    private final Supplier<String> osName;
    private final Supplier<Path> userHome;

    public ClientRegistry() {
        this(() -> System.getProperty("os.name", ""),
             () -> Path.of(System.getProperty("user.home")));
    }

    ClientRegistry(Supplier<String> osName, Supplier<Path> userHome) {
        this.osName = osName;
        this.userHome = userHome;
    }

    public List<AiClient> discover() {
        var home = userHome.get();
        var out = new ArrayList<AiClient>(4);
        out.add(claudeDesktop(home));
        out.add(claudeCode(home));
        out.add(cursor(home));
        out.add(new AiClient(ClientId.GENERIC, ClientId.GENERIC.displayName(), null, true));
        return List.copyOf(out);
    }

    private AiClient claudeDesktop(Path home) {
        Path path;
        var os = osName.get().toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            path = home.resolve("Library/Application Support/Claude/claude_desktop_config.json");
        } else if (os.contains("win")) {
            var appData = System.getenv("APPDATA");
            var base = (appData == null || appData.isBlank()) ? home : Path.of(appData);
            path = base.resolve("Claude").resolve("claude_desktop_config.json");
        } else {
            path = home.resolve(".config/Claude/claude_desktop_config.json");
        }
        return new AiClient(ClientId.CLAUDE_DESKTOP, ClientId.CLAUDE_DESKTOP.displayName(),
            path, Files.isDirectory(path.getParent()));
    }

    private AiClient claudeCode(Path home) {
        var path = home.resolve(".claude.json");
        return new AiClient(ClientId.CLAUDE_CODE, ClientId.CLAUDE_CODE.displayName(),
            path, Files.isRegularFile(path));
    }

    private AiClient cursor(Path home) {
        var path = home.resolve(".cursor/mcp.json");
        return new AiClient(ClientId.CURSOR, ClientId.CURSOR.displayName(),
            path, Files.isDirectory(path.getParent()));
    }
}
