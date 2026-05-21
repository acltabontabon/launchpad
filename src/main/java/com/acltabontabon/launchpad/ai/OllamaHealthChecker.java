package com.acltabontabon.launchpad.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OllamaHealthChecker {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Pattern MODEL_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private final String baseUrl;
    private final String configuredModel;
    private final HttpClient http;

    public OllamaHealthChecker(
        @Value("${spring.ai.ollama.base-url}") String baseUrl,
        @Value("${spring.ai.ollama.chat.options.model}") String configuredModel
    ) {
        this.baseUrl = baseUrl;
        this.configuredModel = configuredModel;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    public OllamaStatus check() {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/tags"))
            .timeout(TIMEOUT)
            .GET()
            .build();

        String body;
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return OllamaStatus.daemonDown(baseUrl);
            }
            body = response.body();
        } catch (Exception e) {
            return OllamaStatus.daemonDown(baseUrl);
        }

        Matcher m = MODEL_NAME.matcher(body);
        while (m.find()) {
            if (matches(m.group(1), configuredModel)) {
                return OllamaStatus.ready(configuredModel);
            }
        }
        return OllamaStatus.modelMissing(configuredModel);
    }

    private static boolean matches(String installed, String configured) {
        // Ollama returns names like "llama3.2:latest" for a "llama3.2" pull.
        if (installed.equals(configured)) return true;
        int colon = installed.indexOf(':');
        return colon > 0 && installed.substring(0, colon).equals(configured);
    }
}
