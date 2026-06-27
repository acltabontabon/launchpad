package com.acltabontabon.launchpad.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Shared HTTP plumbing for provider health probes. Bounds every request with a
 * short timeout so a hung daemon cannot stall a health check, and offers a
 * lightweight model-presence match over the JSON model-listing endpoints
 * (Ollama's {@code /api/tags}, OpenAI's {@code /v1/models}) without pulling in a
 * JSON parser for what is essentially a sanity check.
 */
@Component
public class ProviderProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    // Both endpoints embed model identifiers as JSON "id" or "name" fields. A
    // single regex with two alternatives captures both shapes.
    private static final Pattern MODEL_ID = Pattern.compile("\"(?:name|id)\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient http;

    public ProviderProbe() {
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** GET the url (optionally bearer-authed); returns the body on HTTP 200, else null. */
    public String fetch(String url, String apiKey) {
        Map<String, String> headers = (apiKey != null && !apiKey.isBlank())
            ? Map.of("Authorization", "Bearer " + apiKey)
            : Map.of();
        return fetch(url, headers);
    }

    /**
     * GET the url with arbitrary headers; returns the body on HTTP 200, else
     * null. Lets vendors that authenticate outside the {@code Authorization:
     * Bearer} convention (Anthropic's {@code x-api-key} + {@code anthropic-version})
     * reuse the same bounded-timeout probe.
     */
    public String fetch(String url, Map<String, String> headers) {
        try {
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET();
            headers.forEach(builder::header);
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Apply the provider-specific matcher against every model id in the response body. */
    public boolean matchModel(String body, String configured, ModelMatcher matcher) {
        Matcher m = MODEL_ID.matcher(body);
        while (m.find()) {
            if (matcher.matches(m.group(1), configured)) return true;
        }
        return false;
    }

    @FunctionalInterface
    public interface ModelMatcher {
        boolean matches(String installed, String configured);
    }
}
