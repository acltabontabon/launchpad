package com.acltabontabon.launchpad.mcp;

import java.util.Map;

/** Argument guards shared by every {@code @McpTool} method. */
final class McpArgs {

    private McpArgs() {}

    /**
     * Null/blank guard for a required text argument. Returns a ready-to-return
     * {@code missing_argument} error payload, or {@code null} when the value is
     * present - mirroring the null-when-ok contract of
     * {@link LaunchpadMcpTools#requireSupported}. The offending field name is
     * carried in {@code details.field} so a client can act on it without parsing
     * the message.
     */
    static Map<String, Object> requireText(String field, String value) {
        if (value != null && !value.isBlank()) return null;
        return McpError.invalidArgument(
            "missing_argument",
            "The `" + field + "` argument is required but was missing or blank.",
            "Pass a non-empty value for `" + field + "`.")
            .withDetails(Map.of("field", field))
            .toPayload();
    }
}
