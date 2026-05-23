package com.acltabontabon.launchpad.tui.mcp;

import java.util.List;
import java.util.Map;

/**
 * The JSON-shaped MCP server entry merged under {@code mcpServers.launchpad}.
 * {@code env} may be empty; it is omitted from the rendered JSON in that case.
 */
public record McpSnippet(String command, List<String> args, Map<String, String> env) {

    public McpSnippet {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}
