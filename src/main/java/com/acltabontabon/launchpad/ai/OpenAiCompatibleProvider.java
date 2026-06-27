package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible {@link PreparationProvider} for any local endpoint (LM
 * Studio, llama.cpp server, vLLM, hosted gateways). The api key is optional -
 * local servers typically ignore it but the SDK requires a non-null string, so a
 * placeholder is substituted when the user leaves it blank. Probes
 * {@code /v1/models} for health.
 */
@Component
@Order(20)
public class OpenAiCompatibleProvider implements PreparationProvider {

    /**
     * Placeholder the OpenAI SDK accepts when the local server does not enforce
     * auth. LM Studio and llama.cpp ignore the Authorization header entirely.
     */
    private static final String NO_AUTH_PLACEHOLDER = "not-needed";

    private final LaunchpadAiProperties properties;
    private final ProviderProbe probe;

    public OpenAiCompatibleProvider(LaunchpadAiProperties properties, ProviderProbe probe) {
        this.properties = properties;
        this.probe = probe;
    }

    @Override
    public String id() {
        return LlmProvider.OPENAI_COMPATIBLE.slug();
    }

    @Override
    public ChatModel build(Snapshot snap) {
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

    @Override
    public LlmProviderStatus check(Snapshot snap) {
        var body = probe.fetch(snap.baseUrl() + "/v1/models", snap.apiKey());
        if (body == null) return LlmProviderStatus.daemonDown(LlmProvider.OPENAI_COMPATIBLE, snap.baseUrl());
        return probe.matchModel(body, snap.model(), String::equals)
            ? LlmProviderStatus.ready(LlmProvider.OPENAI_COMPATIBLE, snap.model())
            : LlmProviderStatus.modelMissing(LlmProvider.OPENAI_COMPATIBLE, snap.model());
    }
}
