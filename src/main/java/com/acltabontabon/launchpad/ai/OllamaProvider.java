package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ollama-backed {@link PreparationProvider}. Builds an {@link OllamaChatModel}
 * bound to the configured base URL and model - wrapping every HTTP call with the
 * configured connect+read timeouts so a hung daemon cannot freeze the calling
 * thread - and probes {@code /api/tags} for health.
 */
@Component
@Order(10)
public class OllamaProvider implements PreparationProvider {

    private final HttpTimeouts http;
    private final int numCtx;
    private final ProviderProbe probe;

    public OllamaProvider(LaunchpadAiProperties properties, ProviderProbe probe) {
        this.http = new HttpTimeouts(properties);
        this.numCtx = properties.ollama().numCtx();
        this.probe = probe;
    }

    @Override
    public String id() {
        return LlmProvider.OLLAMA.slug();
    }

    @Override
    public ChatModel build(Snapshot snap) {
        var api = OllamaApi.builder()
            .baseUrl(snap.baseUrl())
            .restClientBuilder(http.restClient())
            .webClientBuilder(http.webClient())
            .build();
        var opts = OllamaChatOptions.builder()
            .model(snap.model())
            .numCtx(numCtx)
            .build();
        return OllamaChatModel.builder()
            .ollamaApi(api)
            .defaultOptions(opts)
            .build();
    }

    @Override
    public LlmProviderStatus check(Snapshot snap) {
        var body = probe.fetch(snap.baseUrl() + "/api/tags", null);
        if (body == null) return LlmProviderStatus.daemonDown(LlmProvider.OLLAMA, snap.baseUrl());
        return probe.matchModel(body, snap.model(), OllamaProvider::ollamaMatch)
            ? LlmProviderStatus.ready(LlmProvider.OLLAMA, snap.model())
            : LlmProviderStatus.modelMissing(LlmProvider.OLLAMA, snap.model());
    }

    /** Ollama returns names like "llama3.2:latest" for a "llama3.2" pull. */
    private static boolean ollamaMatch(String installed, String configured) {
        if (installed.equals(configured)) return true;
        int colon = installed.indexOf(':');
        return colon > 0 && installed.substring(0, colon).equals(configured);
    }
}
