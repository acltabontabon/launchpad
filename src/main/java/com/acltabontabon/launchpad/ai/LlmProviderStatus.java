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
            // this path; the arm keeps the switch exhaustive.
            case DETERMINISTIC -> null;
        };
        String label = attempted == LlmProvider.AUTO
            ? "Local AI not reachable at " + baseUrl
            : attempted.displayName() + " not reachable at " + baseUrl;
        return new LlmProviderStatus(State.DAEMON_DOWN, attempted, label, hint);
    }

    public static LlmProviderStatus modelMissing(LlmProvider provider, String model) {
        String hint = switch (provider) {
            case OLLAMA -> "Run: ollama pull " + model;
            case OPENAI_COMPATIBLE -> "Load model '" + model + "' in your local server";
            case AUTO -> "Load model '" + model + "' in your local server";
            // The deterministic provider has no model to load; the arm keeps the
            // switch exhaustive.
            case DETERMINISTIC -> null;
        };
        return new LlmProviderStatus(
            State.MODEL_MISSING, provider, "Model '" + model + "' is not loaded", hint);
    }

    public boolean isReady() {
        return state == State.READY;
    }
}
