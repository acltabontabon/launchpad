package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync;
import java.util.Map;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Anthropic (Claude) {@link PreparationProvider} - the first paid/cloud synthesis
 * backend on the SPI. Opt-in only: {@link #autoDetectable()} is {@code false}, so
 * {@link LlmProvider#AUTO} never routes preparation to a paid API even when a key
 * is present; the user must pin {@code launchpad.ai.provider=anthropic} explicitly.
 *
 * <p>Authentication is vendor-specific: it prefers the {@code launchpad.ai.anthropic.api-key}
 * key (bound from {@code LAUNCHPAD_ANTHROPIC_API_KEY}) and falls back to the shared
 * snapshot key. A missing key or an unreachable endpoint degrades cleanly to
 * deterministic-only output - {@link #check(Snapshot)} reports not-ready before any
 * generation runs, and {@link #build(Snapshot)} stays safe to construct regardless.
 * Probes {@code /v1/models} with Anthropic's {@code x-api-key} + {@code anthropic-version}
 * headers for health.
 */
@Component
@Order(30)
public class AnthropicProvider implements PreparationProvider {

    /**
     * Substituted when no key is configured so the Anthropic client construction
     * never fails. The router builds its delegate eagerly, but an unauthenticated
     * provider reports not-ready, so this placeholder model is never actually called.
     */
    private static final String NOT_CONFIGURED_PLACEHOLDER = "not-configured";

    /**
     * Upper bound on synthesised tokens. Anthropic requires {@code max_tokens}; the
     * synthesis layer caps each fragment well under this, so it is a guard rail, not
     * a target.
     */
    private static final int MAX_TOKENS = 1024;

    private final LaunchpadAiProperties properties;
    private final ProviderProbe probe;

    public AnthropicProvider(LaunchpadAiProperties properties, ProviderProbe probe) {
        this.properties = properties;
        this.probe = probe;
    }

    @Override
    public String id() {
        return LlmProvider.ANTHROPIC.slug();
    }

    /** Paid/cloud: never auto-selected. The user must pin it explicitly. */
    @Override
    public boolean autoDetectable() {
        return false;
    }

    @Override
    public ChatModel build(Snapshot snap) {
        var apiKey = resolveApiKey(snap);
        if (apiKey.isBlank()) apiKey = NOT_CONFIGURED_PLACEHOLDER;
        var baseUrl = properties.anthropic().baseUrl();
        var timeout = properties.readTimeout();
        // The official Anthropic SDK rebuilds an async client from the
        // environment when one is not supplied, and that rebuild ignores our
        // base URL / api key. Wire both clients explicitly, mirroring the
        // OpenAI-compatible provider.
        var sync = AnthropicOkHttpClient.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .timeout(timeout)
            .build();
        var async = AnthropicOkHttpClientAsync.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .timeout(timeout)
            .build();
        var opts = AnthropicChatOptions.builder()
            .model(snap.model())
            .maxTokens(MAX_TOKENS)
            .build();
        return AnthropicChatModel.builder()
            .anthropicClient(sync)
            .anthropicClientAsync(async)
            .options(opts)
            .build();
    }

    @Override
    public LlmProviderStatus check(Snapshot snap) {
        var apiKey = resolveApiKey(snap);
        if (apiKey.isBlank()) {
            return LlmProviderStatus.unavailable(LlmProvider.ANTHROPIC,
                "Anthropic API key is not configured",
                "Set LAUNCHPAD_ANTHROPIC_API_KEY (or launchpad.ai.api-key) and select a Claude model");
        }
        var body = probe.fetch(properties.anthropic().baseUrl() + "/v1/models", Map.of(
            "x-api-key", apiKey,
            "anthropic-version", properties.anthropic().version()));
        if (body == null) {
            return LlmProviderStatus.unavailable(LlmProvider.ANTHROPIC,
                "Anthropic not reachable - check the API key and network",
                "Verify the Anthropic API key is valid and api.anthropic.com is reachable");
        }
        return probe.matchModel(body, snap.model(), AnthropicProvider::anthropicMatch)
            ? LlmProviderStatus.ready(LlmProvider.ANTHROPIC, snap.model())
            : LlmProviderStatus.modelMissing(LlmProvider.ANTHROPIC, snap.model(),
                "Set an available Claude model id (e.g. claude-sonnet-4-5)");
    }

    /** Vendor key first, then the shared snapshot key; blank means unconfigured. */
    private String resolveApiKey(Snapshot snap) {
        var vendor = properties.anthropic().apiKey();
        if (vendor != null && !vendor.isBlank()) return vendor;
        return snap.hasApiKey() ? snap.apiKey() : "";
    }

    /**
     * Exact match first. Then a narrow alias tolerance: a configured {@code -latest}
     * alias matches a listed dated id sharing the same family stem (e.g.
     * {@code claude-sonnet-4-5-latest} matches {@code claude-sonnet-4-5-20250930}).
     * Deliberately not a broad substring match.
     */
    private static boolean anthropicMatch(String listed, String configured) {
        if (listed.equals(configured)) return true;
        if (configured.endsWith("-latest")) {
            var stem = configured.substring(0, configured.length() - "-latest".length());
            return listed.startsWith(stem + "-");
        }
        return false;
    }
}
