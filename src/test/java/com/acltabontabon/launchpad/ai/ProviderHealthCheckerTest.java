package com.acltabontabon.launchpad.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadAiProperties.Synthesis;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Probes each provider against a stub HTTP server (no Spring, no Ollama, no
 * OpenAI). Covers (a) ready-state model matching for both providers, (b) the
 * Ollama {@code :latest} suffix tolerance, and (c) the AUTO resolution order
 * preferring Ollama's /api/tags before falling back to /v1/models.
 */
class ProviderHealthCheckerTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    // Vendor key used to build the Anthropic provider in newChecker(); blank by
    // default so the paid provider stays inert for the local/AUTO scenarios.
    private String anthropicKey = "";

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
    void ollamaReadyWhenModelListed() {
        respondAt("/api/tags", 200,
            "{\"models\":[{\"name\":\"qwen2.5-coder:7b\"},{\"name\":\"llama3.2:latest\"}]}");
        snapshot.set(new Snapshot(LlmProvider.OLLAMA, baseUrl, "qwen2.5-coder:7b", null, null, null));

        var status = newChecker().check();

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.READY);
        assertThat(status.resolvedProvider()).isEqualTo(LlmProvider.OLLAMA);
    }

    @Test
    void ollamaToleratesLatestSuffix() {
        respondAt("/api/tags", 200, "{\"models\":[{\"name\":\"llama3.2:latest\"}]}");
        snapshot.set(new Snapshot(LlmProvider.OLLAMA, baseUrl, "llama3.2", null, null, null));

        var status = newChecker().check();

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.READY);
    }

    @Test
    void ollamaReportsModelMissingWhenNotListed() {
        respondAt("/api/tags", 200, "{\"models\":[{\"name\":\"other-model\"}]}");
        snapshot.set(new Snapshot(LlmProvider.OLLAMA, baseUrl, "qwen2.5-coder:7b", null, null, null));

        var status = newChecker().check();

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.MODEL_MISSING);
    }

    @Test
    void openAiReadyWhenModelIdMatches() {
        respondAt("/v1/models", 200,
            "{\"data\":[{\"id\":\"llama-3.2-3b\"},{\"id\":\"other-model\"}]}");
        snapshot.set(new Snapshot(LlmProvider.OPENAI_COMPATIBLE, baseUrl, "llama-3.2-3b", null, null, null));

        var status = newChecker().check();

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.READY);
        assertThat(status.resolvedProvider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
    }

    @Test
    void autoPicksOllamaWhenBothEndpointsRespond() {
        respondAt("/api/tags", 200, "{\"models\":[{\"name\":\"qwen2.5-coder:7b\"}]}");
        respondAt("/v1/models", 200, "{\"data\":[{\"id\":\"qwen2.5-coder:7b\"}]}");
        snapshot.set(new Snapshot(LlmProvider.AUTO, baseUrl, "qwen2.5-coder:7b", null, null, null));

        var status = newChecker().check();

        assertThat(status.resolvedProvider()).isEqualTo(LlmProvider.OLLAMA);
    }

    @Test
    void autoFallsThroughToOpenAiWhenOllamaEndpointMissing() {
        respondAt("/v1/models", 200, "{\"data\":[{\"id\":\"local-model\"}]}");
        snapshot.set(new Snapshot(LlmProvider.AUTO, baseUrl, "local-model", null, null, null));

        var status = newChecker().check();

        assertThat(status.resolvedProvider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.READY);
    }

    @Test
    void daemonDownWhenBaseUrlUnreachable() {
        // Stop the server so the connect fails immediately.
        server.stop(0);
        server = null;
        snapshot.set(new Snapshot(LlmProvider.OLLAMA, baseUrl, "any", null, null, null));

        var status = newChecker().check();

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.DAEMON_DOWN);
    }

    @Test
    void autoReturnsModelMissingFromReachableProvider() {
        // Ollama answers but lacks the model: reachable, so AUTO stops here and
        // surfaces its provider-specific MODEL_MISSING rather than probing on.
        respondAt("/api/tags", 200, "{\"models\":[{\"name\":\"other-model\"}]}");
        respondAt("/v1/models", 200, "{\"data\":[{\"id\":\"local-model\"}]}");
        snapshot.set(new Snapshot(LlmProvider.AUTO, baseUrl, "local-model", null, null, null));

        var status = newChecker().check();

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.MODEL_MISSING);
        assertThat(status.resolvedProvider()).isEqualTo(LlmProvider.OLLAMA);
    }

    @Test
    void deterministicProviderReadyWithoutNetwork() {
        // No server: a deterministic check must not touch the network.
        server.stop(0);
        server = null;
        snapshot.set(new Snapshot(LlmProvider.DETERMINISTIC, baseUrl, "unused", null, null, null));

        var status = newChecker().check();

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.READY);
        assertThat(status.resolvedProvider()).isEqualTo(LlmProvider.DETERMINISTIC);
    }

    @Test
    void anthropicPinnedReadyWithVendorKey() {
        anthropicKey = "sk-ant-test";
        respondAt("/v1/models", 200, "{\"data\":[{\"id\":\"claude-sonnet-4-5\"}]}");
        snapshot.set(new Snapshot(LlmProvider.ANTHROPIC, baseUrl, "claude-sonnet-4-5", null, null, null));

        var status = newChecker().check();

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.READY);
        assertThat(status.resolvedProvider()).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void autoNeverSelectsAnthropicEvenWhenReachable() {
        // Anthropic is configured and reachable (vendor key + /v1/models answers),
        // but a paid provider must never be chosen by AUTO. Only the local,
        // auto-detectable OpenAI-compatible provider may win here.
        anthropicKey = "sk-ant-test";
        respondAt("/v1/models", 200, "{\"data\":[{\"id\":\"local-model\"}]}");
        snapshot.set(new Snapshot(LlmProvider.AUTO, baseUrl, "local-model", null, null, null));

        var status = newChecker().check();

        assertThat(status.resolvedProvider()).isNotEqualTo(LlmProvider.ANTHROPIC);
        assertThat(status.resolvedProvider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
    }

    @Test
    void synthesisDisabledResolvesDeterministicRegardlessOfProvider() {
        // Provider pinned to Ollama, but synthesis is off globally: deterministic wins.
        server.stop(0);
        server = null;
        snapshot.set(new Snapshot(LlmProvider.OLLAMA, baseUrl, "qwen2.5-coder:7b", null, null, null));

        var status = newChecker(false).check();

        assertThat(status.state()).isEqualTo(LlmProviderStatus.State.READY);
        assertThat(status.resolvedProvider()).isEqualTo(LlmProvider.DETERMINISTIC);
    }

    private void respondAt(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private ProviderHealthChecker newChecker() {
        return newChecker(true);
    }

    private ProviderHealthChecker newChecker(boolean synthesisEnabled) {
        var props = new LaunchpadAiProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(2), null,
            new LaunchpadAiProperties.Anthropic(baseUrl, "2023-06-01", anthropicKey),
            new Synthesis(synthesisEnabled, null, null));
        var probe = new ProviderProbe();
        var registry = new ProviderRegistry(List.of(
            new OllamaProvider(props, probe),
            new OpenAiCompatibleProvider(props, probe),
            new AnthropicProvider(props, probe),
            new DeterministicProvider()));
        return new ProviderHealthChecker(new StubSettings(snapshot), props, registry);
    }

    /** LaunchpadSettings stub that reads from an AtomicReference instead of disk. */
    private static final class StubSettings extends LaunchpadSettings {
        private final AtomicReference<Snapshot> snap;
        StubSettings(AtomicReference<Snapshot> snap) {
            super("auto", "http://unused", "unused", "", event -> {});
            this.snap = snap;
        }
        @Override public Snapshot snapshot() { return snap.get(); }
    }
}
