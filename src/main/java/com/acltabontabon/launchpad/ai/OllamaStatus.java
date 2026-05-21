package com.acltabontabon.launchpad.ai;

public record OllamaStatus(State state, String message, String hint) {

    public enum State { CHECKING, READY, DAEMON_DOWN, MODEL_MISSING }

    public static OllamaStatus checking() {
        return new OllamaStatus(State.CHECKING, "Checking Ollama...", null);
    }

    public static OllamaStatus ready(String model) {
        return new OllamaStatus(State.READY, "Ollama ready - " + model, null);
    }

    public static OllamaStatus daemonDown(String baseUrl) {
        return new OllamaStatus(
            State.DAEMON_DOWN,
            "Ollama not reachable at " + baseUrl,
            "Run: ollama serve"
        );
    }

    public static OllamaStatus modelMissing(String model) {
        return new OllamaStatus(
            State.MODEL_MISSING,
            "Model '" + model + "' is not pulled",
            "Run: ollama pull " + model
        );
    }

    public boolean isReady() {
        return state == State.READY;
    }
}
