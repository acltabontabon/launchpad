package com.acltabontabon.launchpad.tui.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * Pure JSON merge: takes the existing config file contents (may be empty or null)
 * and returns the same JSON with {@code mcpServers.launchpad} set to the given
 * snippet. Throws typed sentinels when the existing file would be unsafe to mutate.
 */
final class JsonMcpMerger {

    private static final String SERVERS_KEY = "mcpServers";
    private static final String LAUNCHPAD_KEY = "launchpad";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonMcpMerger() {}

    static class KeyAlreadyPresentException extends RuntimeException {
        KeyAlreadyPresentException() { super("mcpServers.launchpad already present"); }
    }

    static class McpServersNotObjectException extends RuntimeException {
        McpServersNotObjectException(String actualType) {
            super("mcpServers exists but is " + actualType + ", not an object");
        }
    }

    static String merge(String existingJson, McpSnippet snippet) {
        ObjectNode root;
        if (existingJson == null || existingJson.isBlank()) {
            root = MAPPER.createObjectNode();
        } else {
            try {
                var parsed = MAPPER.readTree(existingJson);
                if (parsed == null || parsed.isNull() || parsed.isMissingNode()) {
                    root = MAPPER.createObjectNode();
                } else if (!parsed.isObject()) {
                    throw new McpServersNotObjectException("root is " + nodeKind(parsed));
                } else {
                    root = (ObjectNode) parsed;
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Cannot parse existing config as JSON: " + e.getOriginalMessage(), e);
            }
        }

        var serversNode = root.get(SERVERS_KEY);
        ObjectNode servers;
        if (serversNode == null || serversNode.isNull() || serversNode.isMissingNode()) {
            servers = root.putObject(SERVERS_KEY);
        } else if (serversNode.isObject()) {
            servers = (ObjectNode) serversNode;
        } else {
            throw new McpServersNotObjectException(nodeKind(serversNode));
        }

        if (servers.has(LAUNCHPAD_KEY)) {
            throw new KeyAlreadyPresentException();
        }

        var entry = servers.putObject(LAUNCHPAD_KEY);
        entry.put("command", snippet.command());
        ArrayNode args = entry.putArray("args");
        for (var a : snippet.args()) args.add(a);
        if (!snippet.env().isEmpty()) {
            var env = entry.putObject("env");
            for (Map.Entry<String, String> e : snippet.env().entrySet()) {
                env.put(e.getKey(), e.getValue());
            }
        }

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize merged JSON", e);
        }
    }

    /**
     * Best-effort check for an existing {@code mcpServers.launchpad} entry in a
     * JSON config file. Returns false on missing file, parse errors, or any
     * structural mismatch - the goal is to drive a UI badge, not gate a write.
     */
    static boolean hasLaunchpadEntry(String existingJson) {
        if (existingJson == null || existingJson.isBlank()) return false;
        try {
            var tree = MAPPER.readTree(existingJson);
            if (tree == null || !tree.isObject()) return false;
            var servers = tree.get(SERVERS_KEY);
            return servers != null && servers.isObject() && servers.has(LAUNCHPAD_KEY);
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private static String nodeKind(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isArray()) return "an array";
        if (node.isTextual()) return "a string";
        if (node.isNumber()) return "a number";
        if (node.isBoolean()) return "a boolean";
        return "a " + node.getNodeType().toString().toLowerCase();
    }
}
