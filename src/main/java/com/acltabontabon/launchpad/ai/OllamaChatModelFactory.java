package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * Builds an {@link OllamaChatModel} bound to the configured base URL and model.
 * Wraps every HTTP call with the {@link LaunchpadAiProperties} connect+read
 * timeouts so a hung daemon cannot freeze the calling thread.
 */
final class OllamaChatModelFactory {

    private final HttpTimeouts http;

    OllamaChatModelFactory(LaunchpadAiProperties properties) {
        this.http = new HttpTimeouts(properties);
    }

    OllamaChatModel build(Snapshot snap) {
        var api = OllamaApi.builder()
            .baseUrl(snap.baseUrl())
            .restClientBuilder(http.restClient())
            .webClientBuilder(http.webClient())
            .build();
        var opts = OllamaChatOptions.builder().model(snap.model()).build();
        return OllamaChatModel.builder()
            .ollamaApi(api)
            .defaultOptions(opts)
            .build();
    }
}
