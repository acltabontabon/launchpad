package com.acltabontabon.launchpad.tui.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientRegistryTest {

    @Test
    void macOsResolvesClaudeDesktopUnderApplicationSupport(@TempDir Path home) {
        var registry = new ClientRegistry(() -> "Mac OS X", () -> home);
        var clients = registry.discover();

        var desktop = clients.stream().filter(c -> c.id() == ClientId.CLAUDE_DESKTOP).findFirst().orElseThrow();
        assertThat(desktop.configPath()).isEqualTo(
            home.resolve("Library/Application Support/Claude/claude_desktop_config.json"));
    }

    @Test
    void linuxResolvesClaudeDesktopUnderXdgConfig(@TempDir Path home) {
        var registry = new ClientRegistry(() -> "Linux", () -> home);
        var clients = registry.discover();

        var desktop = clients.stream().filter(c -> c.id() == ClientId.CLAUDE_DESKTOP).findFirst().orElseThrow();
        assertThat(desktop.configPath()).isEqualTo(home.resolve(".config/Claude/claude_desktop_config.json"));
    }

    @Test
    void cursorPathSitsUnderDotCursor(@TempDir Path home) {
        var registry = new ClientRegistry(() -> "Mac OS X", () -> home);
        var cursor = registry.discover().stream()
            .filter(c -> c.id() == ClientId.CURSOR).findFirst().orElseThrow();
        assertThat(cursor.configPath()).isEqualTo(home.resolve(".cursor/mcp.json"));
    }

    @Test
    void claudeCodeLivesAtHomeRoot(@TempDir Path home) {
        var registry = new ClientRegistry(() -> "Mac OS X", () -> home);
        var code = registry.discover().stream()
            .filter(c -> c.id() == ClientId.CLAUDE_CODE).findFirst().orElseThrow();
        assertThat(code.configPath()).isEqualTo(home.resolve(".claude.json"));
    }

    @Test
    void detectedReflectsParentDirOrFilePresence(@TempDir Path home) throws Exception {
        // Cursor: detected when ~/.cursor exists as a directory.
        var registry = new ClientRegistry(() -> "Mac OS X", () -> home);
        assertThat(registry.discover().stream()
            .filter(c -> c.id() == ClientId.CURSOR).findFirst().orElseThrow().detected()).isFalse();

        Files.createDirectories(home.resolve(".cursor"));
        assertThat(registry.discover().stream()
            .filter(c -> c.id() == ClientId.CURSOR).findFirst().orElseThrow().detected()).isTrue();

        // Claude Code: detected when ~/.claude.json exists as a file.
        assertThat(registry.discover().stream()
            .filter(c -> c.id() == ClientId.CLAUDE_CODE).findFirst().orElseThrow().detected()).isFalse();
        Files.writeString(home.resolve(".claude.json"), "{}");
        assertThat(registry.discover().stream()
            .filter(c -> c.id() == ClientId.CLAUDE_CODE).findFirst().orElseThrow().detected()).isTrue();
    }

    @Test
    void genericIsAlwaysPresentAndAlwaysReady(@TempDir Path home) {
        var registry = new ClientRegistry(() -> "Mac OS X", () -> home);
        var generic = registry.discover().stream()
            .filter(c -> c.id() == ClientId.GENERIC).findFirst().orElseThrow();
        assertThat(generic.detected()).isTrue();
        assertThat(generic.configPath()).isNull();
    }
}
