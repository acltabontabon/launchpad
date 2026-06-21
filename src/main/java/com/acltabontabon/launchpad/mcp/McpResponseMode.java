package com.acltabontabon.launchpad.mcp;

import java.util.Locale;

/**
 * How Launchpad's body-heavy MCP tools project their successful responses.
 *
 * <p>{@link #REFERENCES} (the default) returns stable ids, project-relative
 * paths, doc anchors, content hashes, and short summaries - pointers an
 * in-session sandbox/indexer (e.g. context-mode) can fetch on demand - instead
 * of inlining full rule and document bodies. {@link #INLINE} preserves the
 * legacy response shape that inlines full bodies, for clients with no sandbox.
 *
 * <p>An MCP server cannot observe sibling servers connected to the same host
 * (the {@code initialize} handshake only exposes the host's own clientInfo and
 * capabilities), so the mode is an explicit opt-out rather than something we can
 * auto-detect: references is the default and inline is selected deliberately.
 */
public enum McpResponseMode {
    REFERENCES,
    INLINE;

    /** Env var that, when set, overrides the property and the default. */
    public static final String ENV_VAR = "LAUNCHPAD_MCP_RESPONSE_MODE";

    /** Spring/system property checked when the env var is unset. */
    public static final String PROPERTY = "launchpad.mcp.response-mode";

    /**
     * Resolve the active mode from raw configuration values, applying the
     * precedence env var &gt; property &gt; default. Both arguments are the raw,
     * untrimmed strings (or {@code null}) read from
     * {@code System.getenv(ENV_VAR)} and the {@code launchpad.mcp.response-mode}
     * property respectively. Parsing is case-insensitive and trimmed; a blank or
     * unrecognized value at any level falls through to the next, and the final
     * fallback is {@link #REFERENCES}.
     *
     * <p>This deliberately does not depend on Spring relaxed binding so the
     * precedence is explicit and unit-testable in isolation.
     */
    public static McpResponseMode resolve(String rawEnv, String rawProperty) {
        McpResponseMode fromEnv = parse(rawEnv);
        if (fromEnv != null) return fromEnv;
        McpResponseMode fromProperty = parse(rawProperty);
        if (fromProperty != null) return fromProperty;
        return REFERENCES;
    }

    /**
     * Parse a single raw value. Returns {@code null} for blank input (so the
     * caller falls through to the next precedence level) and for unrecognized
     * input (so an obvious typo cannot silently flip the default). Callers that
     * want to surface a typo should compare {@code parse(raw) == null} against
     * {@code raw} being non-blank.
     */
    static McpResponseMode parse(String raw) {
        if (raw == null) return null;
        var normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "references" -> REFERENCES;
            case "inline" -> INLINE;
            default -> null;
        };
    }

    /** True when {@code raw} is a non-blank value that {@link #parse} rejects. */
    static boolean isUnrecognized(String raw) {
        return raw != null && !raw.isBlank() && parse(raw) == null;
    }

    public boolean isInline() {
        return this == INLINE;
    }

    public boolean isReferences() {
        return this == REFERENCES;
    }
}
