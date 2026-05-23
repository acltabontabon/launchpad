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

    public static LlmProviderStatus daemonDown(LlmProvider attempted, String baseUrl) {
        String hint = switch (attempted) {
            case OLLAMA -> "Run: ollama serve";
            case OPENAI_COMPATIBLE -> "Start your OpenAI-compatible server (LM Studio / llama.cpp / vLLM)";
            case AUTO -> "Start Ollama (ollama serve) or an OpenAI-compatible server";
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
        };
        return new LlmProviderStatus(
            State.MODEL_MISSING, provider, "Model '" + model + "' is not loaded", hint);
    }

    public boolean isReady() {
        return state == State.READY;
    }
}
