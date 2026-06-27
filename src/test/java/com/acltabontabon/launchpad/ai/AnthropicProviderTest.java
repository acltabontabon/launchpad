package com.acltabontabon.launchpad.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadAiProperties.Anthropic;
import com.acltabontabon.launchpad.config.LaunchpadAiProperties.Synthesis;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the opt-in Anthropic provider against a stub HTTP server (no Spring,
 * no network to Anthropic). Covers the guardrails that matter for a paid cloud
 * backend: it never probes without a key, it degrades cleanly when unauthenticated
 * or unreachable, it sends Anthropic's {@code x-api-key} / {@code anthropic-version}
 * headers (not {@code Authorization}), the vendor key takes precedence over the
 * shared key, model matching is narrow, and it is not auto-detectable.
 */
class AnthropicProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger probeHits = new AtomicInteger();
    private final AtomicReference<String> seenApiKey = new AtomicReference<>();
    private final AtomicReference<String> seenVersion = new AtomicReference<>();
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void missingKeyReportsNotConfiguredWithoutProbing() {
        respondModels(200, "{\"data\":[{\"type\":\"model\",\"id\":\"claude-sonnet-4-5\"}]}");
        // No vendor key, no snapshot key.
        var status = provider("").check(snapshot("claude-sonnet-4-5", null));

        assertThat(status.isReady()).isFalse();
        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.DAEMON_DOWN);
        assertThat(status.message()).isEqualTo("Anthropic API key is not configured");
        assertThat(probeHits.get()).isZero();
    }

    @Test
    void readyWhenModelListedAndSendsAnthropicHeaders() {
        respondModels(200, "{\"data\":[{\"type\":\"model\",\"id\":\"claude-sonnet-4-5\"}]}");
        var status = provider("sk-ant-vendor").check(snapshot("claude-sonnet-4-5", null));

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.READY);
        assertThat(status.resolvedProvider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(seenApiKey.get()).isEqualTo("sk-ant-vendor");
        assertThat(seenVersion.get()).isEqualTo("2023-06-01");
        assertThat(seenAuthorization.get()).isNull();
    }

    @Test
    void vendorKeyTakesPrecedenceOverSharedKey() {
        respondModels(200, "{\"data\":[{\"id\":\"claude-sonnet-4-5\"}]}");
        // Both set: the vendor key must win.
        provider("sk-ant-vendor").check(snapshot("claude-sonnet-4-5", "sk-ant-shared"));

        assertThat(seenApiKey.get()).isEqualTo("sk-ant-vendor");
    }

    @Test
    void fallsBackToSharedKeyWhenVendorKeyBlank() {
        respondModels(200, "{\"data\":[{\"id\":\"claude-sonnet-4-5\"}]}");
        var status = provider("").check(snapshot("claude-sonnet-4-5", "sk-ant-shared"));

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.READY);
        assertThat(seenApiKey.get()).isEqualTo("sk-ant-shared");
    }

    @Test
    void unreachableOrUnauthorizedDegradesToNotReady() {
        respondModels(401, "{\"error\":\"invalid x-api-key\"}");
        var status = provider("sk-ant-bad").check(snapshot("claude-sonnet-4-5", null));

        assertThat(status.isReady()).isFalse();
        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.DAEMON_DOWN);
        assertThat(status.message()).contains("Anthropic not reachable");
    }

    @Test
    void latestAliasMatchesDatedFamilyId() {
        respondModels(200, "{\"data\":[{\"id\":\"claude-sonnet-4-5-20250930\"}]}");
        var status = provider("sk-ant-vendor").check(snapshot("claude-sonnet-4-5-latest", null));

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.READY);
    }

    @Test
    void unrelatedModelReportsModelMissing() {
        respondModels(200, "{\"data\":[{\"id\":\"claude-sonnet-4-5-20250930\"}]}");
        var status = provider("sk-ant-vendor").check(snapshot("claude-opus-4-1", null));

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.MODEL_MISSING);
    }

    @Test
    void notAutoDetectable() {
        assertThat(provider("sk-ant-vendor").autoDetectable()).isFalse();
    }

    private AnthropicProvider provider(String vendorKey) {
        var props = new LaunchpadAiProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(2), null,
            new Anthropic(baseUrl, "2023-06-01", vendorKey),
            new Synthesis(true, null, null));
        return new AnthropicProvider(props, new ProviderProbe());
    }

    private Snapshot snapshot(String model, String sharedApiKey) {
        return new Snapshot(LlmProvider.ANTHROPIC, "http://unused", model, sharedApiKey, null, null);
    }

    private void respondModels(int status, String body) {
        server.createContext("/v1/models", exchange -> {
            probeHits.incrementAndGet();
            var headers = exchange.getRequestHeaders();
            seenApiKey.set(headers.getFirst("x-api-key"));
            seenVersion.set(headers.getFirst("anthropic-version"));
            seenAuthorization.set(headers.getFirst("Authorization"));
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}
