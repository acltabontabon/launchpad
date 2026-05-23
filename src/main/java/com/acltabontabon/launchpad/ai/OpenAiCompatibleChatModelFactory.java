package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Builds an {@link OpenAiChatModel} pointed at any OpenAI-compatible local
 * endpoint (LM Studio, llama.cpp server, vLLM, hosted gateways). The api key
 * is optional - local servers typically ignore it but the SDK requires a
 * non-null string, so we substitute a placeholder when the user leaves it blank.
 */
final class OpenAiCompatibleChatModelFactory {

    /**
     * Placeholder the OpenAI SDK accepts when the local server does not enforce
     * auth. LM Studio and llama.cpp ignore the Authorization header entirely.
     */
    private static final String NO_AUTH_PLACEHOLDER = "not-needed";

    private final LaunchpadAiProperties properties;

    OpenAiCompatibleChatModelFactory(LaunchpadAiProperties properties) {
        this.properties = properties;
    }

    OpenAiChatModel build(Snapshot snap) {
        var apiKey = snap.hasApiKey() ? snap.apiKey() : NO_AUTH_PLACEHOLDER;
        var sync = OpenAIOkHttpClient.builder()
            .baseUrl(snap.baseUrl())
            .apiKey(apiKey)
            .timeout(properties.readTimeout())
            .build();
        // OpenAiChatModel rebuilds an async client from env if we don't supply one,
        // and that rebuild does not see our base URL or api key. Wire both clients
        // explicitly so a local server (which never reads OPENAI_API_KEY) still works.
        var async = OpenAIOkHttpClientAsync.builder()
            .baseUrl(snap.baseUrl())
            .apiKey(apiKey)
            .timeout(properties.readTimeout())
            .build();
        var opts = OpenAiChatOptions.builder().model(snap.model()).build();
        return OpenAiChatModel.builder()
            .openAiClient(sync)
            .openAiClientAsync(async)
            .options(opts)
            .build();
    }
}
