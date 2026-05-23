package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Probes the configured local-AI endpoint. Supports both Ollama's {@code /api/tags}
 * model-listing endpoint and the OpenAI-compatible {@code /v1/models} endpoint.
 * For {@link LlmProvider#AUTO} the Ollama endpoint is probed first; on miss the
 * OpenAI endpoint is tried so a single base URL works for either backend.
 */
@Component
public class ProviderHealthChecker {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    // Both endpoints embed model identifiers as JSON "id" or "name" fields. A
    // single regex with two alternatives captures both shapes without pulling
    // in a JSON parser for what is essentially a sanity check.
    private static final Pattern MODEL_ID = Pattern.compile("\"(?:name|id)\"\\s*:\\s*\"([^\"]+)\"");

    private final LaunchpadSettings settings;
    private final HttpClient http;

    public ProviderHealthChecker(LaunchpadSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    public LlmProviderStatus check() {
        var snap = settings.snapshot();
        var requested = snap.provider();
        return switch (requested) {
            case OLLAMA -> checkOllama(snap);
            case OPENAI_COMPATIBLE -> checkOpenAi(snap);
            case AUTO -> resolveAndCheck(snap);
        };
    }

    /**
     * Determine which concrete provider should handle traffic when the user
     * has not pinned one. Returns the resolved provider, or {@link LlmProvider#OLLAMA}
     * as a default when neither endpoint responds (so the daemon-down badge is
     * still actionable - the user sees "Ollama not reachable" rather than a
     * generic "no provider responded").
     */
    public LlmProvider resolveAuto(String baseUrl, String apiKey) {
        if (probe(baseUrl + "/api/tags", null)) return LlmProvider.OLLAMA;
        if (probe(baseUrl + "/v1/models", apiKey)) return LlmProvider.OPENAI_COMPATIBLE;
        return LlmProvider.OLLAMA;
    }

    private LlmProviderStatus resolveAndCheck(Snapshot snap) {
        var resolved = resolveAuto(snap.baseUrl(), snap.apiKey());
        // Re-issue the full check against the resolved provider so model-presence
        // verification (not just endpoint liveness) feeds the status.
        return switch (resolved) {
            case OLLAMA -> checkOllama(snap);
            case OPENAI_COMPATIBLE -> checkOpenAi(snap);
            case AUTO -> LlmProviderStatus.daemonDown(LlmProvider.AUTO, snap.baseUrl());
        };
    }

    private LlmProviderStatus checkOllama(Snapshot snap) {
        var body = fetch(snap.baseUrl() + "/api/tags", null);
        if (body == null) return LlmProviderStatus.daemonDown(LlmProvider.OLLAMA, snap.baseUrl());
        return matchModel(body, snap.model(), this::ollamaMatch)
            ? LlmProviderStatus.ready(LlmProvider.OLLAMA, snap.model())
            : LlmProviderStatus.modelMissing(LlmProvider.OLLAMA, snap.model());
    }

    private LlmProviderStatus checkOpenAi(Snapshot snap) {
        var body = fetch(snap.baseUrl() + "/v1/models", snap.apiKey());
        if (body == null) return LlmProviderStatus.daemonDown(LlmProvider.OPENAI_COMPATIBLE, snap.baseUrl());
        return matchModel(body, snap.model(), String::equals)
            ? LlmProviderStatus.ready(LlmProvider.OPENAI_COMPATIBLE, snap.model())
            : LlmProviderStatus.modelMissing(LlmProvider.OPENAI_COMPATIBLE, snap.model());
    }

    private String fetch(String url, String apiKey) {
        try {
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean probe(String url, String apiKey) {
        return fetch(url, apiKey) != null;
    }

    /** Apply the provider-specific matcher against every model id in the response body. */
    private static boolean matchModel(String body, String configured, ModelMatcher matcher) {
        Matcher m = MODEL_ID.matcher(body);
        while (m.find()) {
            if (matcher.matches(m.group(1), configured)) return true;
        }
        return false;
    }

    /** Ollama returns names like "llama3.2:latest" for a "llama3.2" pull. */
    private boolean ollamaMatch(String installed, String configured) {
        if (installed.equals(configured)) return true;
        int colon = installed.indexOf(':');
        return colon > 0 && installed.substring(0, colon).equals(configured);
    }

    @FunctionalInterface
    private interface ModelMatcher {
        boolean matches(String installed, String configured);
    }
}
