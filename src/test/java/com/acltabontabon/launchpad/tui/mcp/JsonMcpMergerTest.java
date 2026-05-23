package com.acltabontabon.launchpad.tui.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonMcpMergerTest {

    private static final McpSnippet SNIPPET = new McpSnippet(
        "java",
        List.of("-jar", "/tmp/launchpad.jar", "mcp"),
        Map.of()
    );

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void writesMcpServersLaunchpadIntoEmptyFile() throws Exception {
        var out = JsonMcpMerger.merge("", SNIPPET);
        var tree = M.readTree(out);
        assertThat(tree.get("mcpServers").get("launchpad").get("command").asText()).isEqualTo("java");
        assertThat(tree.get("mcpServers").get("launchpad").get("args").get(0).asText()).isEqualTo("-jar");
    }

    @Test
    void preservesUnrelatedTopLevelKeys() throws Exception {
        var existing = "{\"someOtherKey\":\"keep me\",\"nested\":{\"x\":1}}";
        var out = JsonMcpMerger.merge(existing, SNIPPET);
        var tree = M.readTree(out);
        assertThat(tree.get("someOtherKey").asText()).isEqualTo("keep me");
        assertThat(tree.get("nested").get("x").asInt()).isEqualTo(1);
        assertThat(tree.get("mcpServers").get("launchpad")).isNotNull();
    }

    @Test
    void preservesSiblingMcpServers() throws Exception {
        var existing = "{\"mcpServers\":{\"otherServer\":{\"command\":\"node\",\"args\":[\"x.js\"]}}}";
        var out = JsonMcpMerger.merge(existing, SNIPPET);
        var tree = M.readTree(out);
        assertThat(tree.get("mcpServers").get("otherServer").get("command").asText()).isEqualTo("node");
        assertThat(tree.get("mcpServers").get("launchpad")).isNotNull();
    }

    @Test
    void throwsWhenLaunchpadKeyAlreadyPresent() {
        var existing = "{\"mcpServers\":{\"launchpad\":{\"command\":\"old\",\"args\":[]}}}";
        assertThatThrownBy(() -> JsonMcpMerger.merge(existing, SNIPPET))
            .isInstanceOf(JsonMcpMerger.KeyAlreadyPresentException.class);
    }

    @Test
    void throwsWhenMcpServersIsString() {
        var existing = "{\"mcpServers\":\"not an object\"}";
        assertThatThrownBy(() -> JsonMcpMerger.merge(existing, SNIPPET))
            .isInstanceOf(JsonMcpMerger.McpServersNotObjectException.class);
    }

    @Test
    void throwsWhenMcpServersIsArray() {
        var existing = "{\"mcpServers\":[1,2,3]}";
        assertThatThrownBy(() -> JsonMcpMerger.merge(existing, SNIPPET))
            .isInstanceOf(JsonMcpMerger.McpServersNotObjectException.class);
    }

    @Test
    void throwsWhenRootIsNotObject() {
        var existing = "[\"not\",\"an\",\"object\"]";
        assertThatThrownBy(() -> JsonMcpMerger.merge(existing, SNIPPET))
            .isInstanceOf(JsonMcpMerger.McpServersNotObjectException.class);
    }

    @Test
    void emitsEnvBlockOnlyWhenEnvIsNonEmpty() throws Exception {
        var withEnv = new McpSnippet("java", List.of("-jar", "x.jar"), Map.of("FOO", "bar"));
        var out = JsonMcpMerger.merge("", withEnv);
        var tree = M.readTree(out);
        assertThat(tree.get("mcpServers").get("launchpad").get("env").get("FOO").asText()).isEqualTo("bar");

        var noEnv = JsonMcpMerger.merge("", SNIPPET);
        var noEnvTree = M.readTree(noEnv);
        assertThat(noEnvTree.get("mcpServers").get("launchpad").has("env")).isFalse();
    }

    @Test
    void hasLaunchpadEntryDetectsExistingKey() {
        assertThat(JsonMcpMerger.hasLaunchpadEntry(
            "{\"mcpServers\":{\"launchpad\":{\"command\":\"x\",\"args\":[]}}}")).isTrue();
        assertThat(JsonMcpMerger.hasLaunchpadEntry(
            "{\"mcpServers\":{\"other\":{\"command\":\"x\",\"args\":[]}}}")).isFalse();
        assertThat(JsonMcpMerger.hasLaunchpadEntry("")).isFalse();
        assertThat(JsonMcpMerger.hasLaunchpadEntry(null)).isFalse();
        assertThat(JsonMcpMerger.hasLaunchpadEntry("not json at all {{{")).isFalse();
        assertThat(JsonMcpMerger.hasLaunchpadEntry("{\"mcpServers\":\"nope\"}")).isFalse();
    }

}
