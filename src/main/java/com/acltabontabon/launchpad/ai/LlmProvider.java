package com.acltabontabon.launchpad.ai;

/**
 * Built-in vocabulary of preparation backends Launchpad talks to. Each value's
 * {@link #slug()} matches the {@code id()} of a {@link PreparationProvider} bean.
 * {@link #AUTO} defers the choice to {@link ProviderHealthChecker} at
 * health-check time; the resolved value is surfaced on {@link LlmProviderStatus}
 * so the Welcome badge can show what was actually picked. {@link #DETERMINISTIC}
 * selects deterministic, no-AI output.
 */
public enum LlmProvider {
    OLLAMA("ollama"),
    OPENAI_COMPATIBLE("openai-compatible"),
    DETERMINISTIC("deterministic"),
    AUTO("auto");

    private final String slug;

    LlmProvider(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }

    /** Parse a slug from config. Null / blank / unknown all fall back to {@link #AUTO}. */
    public static LlmProvider parse(String value) {
        if (value == null) return AUTO;
        var v = value.trim().toLowerCase();
        if (v.isEmpty()) return AUTO;
        for (var p : values()) {
            if (p.slug.equals(v)) return p;
        }
        return AUTO;
    }

    public String displayName() {
        return switch (this) {
            case OLLAMA -> "Ollama";
            case OPENAI_COMPATIBLE -> "OpenAI-compatible";
            case DETERMINISTIC -> "Deterministic (no AI)";
            case AUTO -> "Auto-detect";
        };
    }
}
