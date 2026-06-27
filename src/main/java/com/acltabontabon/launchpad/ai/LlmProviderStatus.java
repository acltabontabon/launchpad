package com.acltabontabon.launchpad.ai;

/**
 * Result of {@link ProviderHealthChecker#check()}. Carries the resolved provider
 * (the concrete backend chosen, even when settings asked for {@link LlmProvider#AUTO})
 * so the Welcome badge can render an honest label.
 */
public record LlmProviderStatus(State state, LlmProvider resolvedProvider, String message, String hint) {

    public enum State { CHECKING, READY, DAEMON_DOWN, MODEL_MISSING }

    public static LlmProviderStatus checking() {
        return new LlmProviderStatus(State.CHECKING, null, "Checking local AI...", null);
    }

    public static LlmProviderStatus ready(LlmProvider provider, String model) {
        return new LlmProviderStatus(
            State.READY, provider, provider.displayName() + " ready - " + model, null);
    }

    /**
     * Ready status for the deterministic (no-AI) provider. Always reachable - it
     * makes no network call - but yields deterministic-only output, so the
     * runtime treats it as {@code aiAvailable == false}.
     */
    public static LlmProviderStatus deterministic() {
        return new LlmProviderStatus(
            State.READY, LlmProvider.DETERMINISTIC, "Deterministic mode - no AI synthesis", null);
    }

    public static LlmProviderStatus daemonDown(LlmProvider attempted, String baseUrl) {
        String hint = switch (attempted) {
            case OLLAMA -> "Run: ollama serve";
            case OPENAI_COMPATIBLE -> "Start your OpenAI-compatible server (LM Studio / llama.cpp / vLLM)";
            case AUTO -> "Start Ollama (ollama serve) or an OpenAI-compatible server";
            // The deterministic provider is always reachable, so it never reaches
            // this path. Other providers (e.g. cloud) carry their own message via
            // unavailable(); the default arm keeps this switch generic.
            default -> null;
        };
        String label = attempted == LlmProvider.AUTO
            ? "Local AI not reachable at " + baseUrl
            : attempted.displayName() + " not reachable at " + baseUrl;
        return new LlmProviderStatus(State.DAEMON_DOWN, attempted, label, hint);
    }

    /**
     * Not-ready status carrying a provider-supplied message and hint, mapped to
     * {@link State#DAEMON_DOWN} so {@link #isReady()} is false and the runtime
     * degrades to deterministic output. Lets a provider phrase its own failure
     * (e.g. "Anthropic API key is not configured") instead of routing through
     * the generic daemon-down wording.
     */
    public static LlmProviderStatus unavailable(LlmProvider provider, String message, String hint) {
        return new LlmProviderStatus(State.DAEMON_DOWN, provider, message, hint);
    }

    public static LlmProviderStatus modelMissing(LlmProvider provider, String model) {
        String hint = switch (provider) {
            case OLLAMA -> "Run: ollama pull " + model;
            case OPENAI_COMPATIBLE -> "Load model '" + model + "' in your local server";
            case AUTO -> "Load model '" + model + "' in your local server";
            // The deterministic provider has no model to load; other providers
            // pass an explicit hint via the three-arg overload. The default arm
            // keeps this switch generic.
            default -> "Select a model available to this provider";
        };
        return modelMissing(provider, model, hint);
    }

    /** Model-missing status with a provider-supplied hint. */
    public static LlmProviderStatus modelMissing(LlmProvider provider, String model, String hint) {
        return new LlmProviderStatus(
            State.MODEL_MISSING, provider, "Model '" + model + "' is not loaded", hint);
    }

    public boolean isReady() {
        return state == State.READY;
    }
}
