package com.acltabontabon.launchpad.tui.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigWriterTest {

    private static final McpSnippet SNIPPET = new McpSnippet(
        "java", List.of("-jar", "/tmp/launchpad.jar", "mcp"), Map.of());

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void writesNewConfigAndDoesNotBackupWhenNoExistingFile(@TempDir Path home) throws Exception {
        var writer = new McpConfigWriter(home);
        var cursorPath = home.resolve(".cursor/mcp.json");
        var client = new AiClient(ClientId.CURSOR, "Cursor", cursorPath, true, false);

        var run = writer.apply(List.of(client), SNIPPET);

        assertThat(run.reports()).hasSize(1);
        assertThat(run.reports().get(0).outcome()).isEqualTo(WriteReport.Outcome.WRITTEN);
        assertThat(run.backupDir()).isNull();
        assertThat(Files.readString(cursorPath)).contains("\"launchpad\"");
    }

    @Test
    void backupsExistingFileBeforeMerging(@TempDir Path home) throws Exception {
        var writer = new McpConfigWriter(home);
        var cursorPath = home.resolve(".cursor/mcp.json");
        Files.createDirectories(cursorPath.getParent());
        var originalBody = "{\"mcpServers\":{\"other\":{\"command\":\"node\",\"args\":[]}}}";
        Files.writeString(cursorPath, originalBody);
        var client = new AiClient(ClientId.CURSOR, "Cursor", cursorPath, true, false);

        var run = writer.apply(List.of(client), SNIPPET);

        assertThat(run.backupDir()).isNotNull();
        var backup = run.backupDir().resolve("cursor").resolve("mcp.json");
        assertThat(Files.readString(backup)).isEqualTo(originalBody);

        var tree = M.readTree(Files.readString(cursorPath));
        assertThat(tree.get("mcpServers").get("other")).isNotNull();
        assertThat(tree.get("mcpServers").get("launchpad").get("command").asText()).isEqualTo("java");
    }

    @Test
    void skipsWhenLaunchpadKeyAlreadyPresent(@TempDir Path home) throws Exception {
        var writer = new McpConfigWriter(home);
        var cursorPath = home.resolve(".cursor/mcp.json");
        Files.createDirectories(cursorPath.getParent());
        var existing = "{\"mcpServers\":{\"launchpad\":{\"command\":\"old\",\"args\":[]}}}";
        Files.writeString(cursorPath, existing);
        var client = new AiClient(ClientId.CURSOR, "Cursor", cursorPath, true, false);

        var run = writer.apply(List.of(client), SNIPPET);

        assertThat(run.reports().get(0).outcome()).isEqualTo(WriteReport.Outcome.SKIPPED_KEY_EXISTS);
        assertThat(Files.readString(cursorPath)).isEqualTo(existing);
    }

    @Test
    void reportsErrorWhenMcpServersIsNotObject(@TempDir Path home) throws Exception {
        var writer = new McpConfigWriter(home);
        var cursorPath = home.resolve(".cursor/mcp.json");
        Files.createDirectories(cursorPath.getParent());
        var existing = "{\"mcpServers\":\"oops\"}";
        Files.writeString(cursorPath, existing);
        var client = new AiClient(ClientId.CURSOR, "Cursor", cursorPath, true, false);

        var run = writer.apply(List.of(client), SNIPPET);

        assertThat(run.reports().get(0).outcome()).isEqualTo(WriteReport.Outcome.ERROR_NOT_OBJECT);
        assertThat(Files.readString(cursorPath)).isEqualTo(existing);
    }

    @Test
    void devModeReportsErrorPerFileWritingClient(@TempDir Path home) {
        var writer = new McpConfigWriter(home);
        var client = new AiClient(ClientId.CURSOR, "Cursor", home.resolve(".cursor/mcp.json"), true, false);

        var run = writer.apply(List.of(client), null);

        assertThat(run.reports().get(0).outcome()).isEqualTo(WriteReport.Outcome.ERROR_DEV_MODE);
    }
}
