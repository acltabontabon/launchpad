package com.acltabontabon.launchpad.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single error envelope every {@code @McpTool} method returns on failure.
 * <p>
 * Wire shape (serialized via {@link #toPayload()}):
 * <pre>
 * {
 *   "error": {
 *     "code": "no_project_model",
 *     "type": "NOT_FOUND",
 *     "message": "...",
 *     "remediation": "...",   // omitted when null
 *     "details": { ... }       // omitted when null
 *   }
 * }
 * </pre>
 * <p>
 * Nesting under a single {@code error} key keeps the success payloads (which use
 * top-level data keys like {@code project}, {@code workflows}) from colliding
 * with error fields and lets MCP clients branch on {@code error.code} or
 * {@code error.type} without enumerating every code. See
 * {@code docs/mcp-errors.adoc} for the documented schema and the full code
 * registry.
 */
public record McpError(
    String code,
    Type type,
    String message,
    String remediation,
    Map<String, Object> details
) {

    public enum Type {
        INVALID_ARGUMENT,
        NOT_FOUND,
        PERMISSION_DENIED,
        UNSUPPORTED,
        RESOURCE_EXHAUSTED,
        INTERNAL
    }

    public McpError(String code, Type type, String message) {
        this(code, type, message, null, null);
    }

    public McpError(String code, Type type, String message, String remediation) {
        this(code, type, message, remediation, null);
    }

    /** Serialize this error into the wire envelope returned by every MCP tool. */
    public Map<String, Object> toPayload() {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("code", code);
        inner.put("type", type.name());
        inner.put("message", message);
        if (remediation != null) inner.put("remediation", remediation);
        if (details != null && !details.isEmpty()) inner.put("details", details);
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("error", inner);
        return envelope;
    }

    // --- Factories grouped by Type, so call sites read like English. ---

    public static McpError invalidArgument(String code, String message) {
        return new McpError(code, Type.INVALID_ARGUMENT, message);
    }

    public static McpError invalidArgument(String code, String message, String remediation) {
        return new McpError(code, Type.INVALID_ARGUMENT, message, remediation);
    }

    public static McpError notFound(String code, String message) {
        return new McpError(code, Type.NOT_FOUND, message);
    }

    public static McpError notFound(String code, String message, String remediation) {
        return new McpError(code, Type.NOT_FOUND, message, remediation);
    }

    public static McpError permissionDenied(String code, String message) {
        return new McpError(code, Type.PERMISSION_DENIED, message);
    }

    public static McpError unsupported(String code, String message) {
        return new McpError(code, Type.UNSUPPORTED, message);
    }

    public static McpError resourceExhausted(String code, String message) {
        return new McpError(code, Type.RESOURCE_EXHAUSTED, message);
    }

    public static McpError internal(String code, String message) {
        return new McpError(code, Type.INTERNAL, message);
    }

    /** Returns a copy with {@code details} attached, leaving other fields untouched. */
    public McpError withDetails(Map<String, Object> details) {
        return new McpError(code, type, message, remediation, details);
    }
}
