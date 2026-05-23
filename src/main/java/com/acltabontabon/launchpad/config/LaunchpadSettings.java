package com.acltabontabon.launchpad.config;

import com.acltabontabon.launchpad.ai.LlmProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class LaunchpadSettings {

    /**
     * Provider-neutral settings snapshot. {@code apiKey} is optional - blank means
     * no Authorization header is sent (matches LM Studio / llama.cpp / unauth vLLM).
     */
    public record Snapshot(
        LlmProvider provider,
        String baseUrl,
        String model,
        String apiKey,
        String remoteStandardsUrl
    ) {
        public boolean hasRemoteStandards() {
            return remoteStandardsUrl != null && !remoteStandardsUrl.isBlank();
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** Avoid leaking the api key in logs or debuggers. */
        @Override
        public String toString() {
            return "Snapshot[provider=" + provider
                + ", baseUrl=" + baseUrl
                + ", model=" + model
                + ", apiKey=" + (hasApiKey() ? "***" : "<unset>")
                + ", remoteStandardsUrl=" + remoteStandardsUrl + "]";
        }
    }

    public record LlmProviderSettingsChanged(Snapshot snapshot) {}

    public record RemoteStandardsSettingsChanged(Snapshot snapshot) {}

    // New provider-neutral keys.
    private static final String PROVIDER_KEY = "launchpad.ai.provider";
    private static final String BASE_URL_KEY = "launchpad.ai.base-url";
    private static final String MODEL_KEY = "launchpad.ai.model";
    private static final String API_KEY = "launchpad.ai.api-key";
    private static final String REMOTE_STANDARDS_URL_KEY = "launchpad.standards.remote.url";

    // Legacy keys read on first run for backward compatibility with pre-0.2 user
    // configs that pinned Ollama explicitly. Never written back.
    private static final String LEGACY_OLLAMA_BASE_URL_KEY = "spring.ai.ollama.base-url";
    private static final String LEGACY_OLLAMA_MODEL_KEY = "spring.ai.ollama.chat.options.model";

    // Env-var escape hatch so users can keep the api key out of plaintext config.
    private static final String API_KEY_ENV = "LAUNCHPAD_LLM_API_KEY";

    private final Path configFile = Path.of(System.getProperty("user.home"), ".launchpad", "config.properties");
    private final AtomicReference<Snapshot> current;
    private final ApplicationEventPublisher events;

    public LaunchpadSettings(
        @Value("${launchpad.ai.provider:auto}") String defaultProvider,
        @Value("${launchpad.ai.base-url:http://localhost:11434}") String defaultBaseUrl,
        @Value("${launchpad.ai.model:qwen2.5-coder:7b-instruct}") String defaultModel,
        @Value("${launchpad.ai.api-key:}") String defaultApiKey,
        ApplicationEventPublisher events
    ) {
        this.events = events;
        this.current = new AtomicReference<>(loadOrDefault(
            LlmProvider.parse(defaultProvider), defaultBaseUrl, defaultModel, defaultApiKey));
    }

    public Snapshot snapshot() {
        return current.get();
    }

    public void update(LlmProvider provider, String baseUrl, String model, String apiKey,
                       String remoteStandardsUrl) throws IOException {
        var previous = current.get();
        var next = new Snapshot(
            provider == null ? LlmProvider.AUTO : provider,
            baseUrl,
            model,
            normalize(apiKey),
            normalize(remoteStandardsUrl)
        );
        writeFile(next);
        current.set(next);
        if (providerSettingsChanged(previous, next)) {
            events.publishEvent(new LlmProviderSettingsChanged(next));
        }
        if (!Objects.equals(previous.remoteStandardsUrl(), next.remoteStandardsUrl())) {
            events.publishEvent(new RemoteStandardsSettingsChanged(next));
        }
    }

    private static boolean providerSettingsChanged(Snapshot a, Snapshot b) {
        return a.provider() != b.provider()
            || !Objects.equals(a.baseUrl(), b.baseUrl())
            || !Objects.equals(a.model(), b.model())
            || !Objects.equals(a.apiKey(), b.apiKey());
    }

    private Snapshot loadOrDefault(LlmProvider defaultProvider, String defaultBaseUrl,
                                   String defaultModel, String defaultApiKey) {
        String envKey = System.getenv(API_KEY_ENV);
        if (!Files.isRegularFile(configFile)) {
            return new Snapshot(defaultProvider, defaultBaseUrl, defaultModel,
                normalize(envKey != null ? envKey : defaultApiKey), null);
        }
        var props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            return new Snapshot(defaultProvider, defaultBaseUrl, defaultModel,
                normalize(envKey != null ? envKey : defaultApiKey), null);
        }
        // Legacy fallback: a pre-0.2 config with only `spring.ai.ollama.*` keys
        // still resolves to a usable Ollama snapshot without manual migration.
        String baseUrl = props.getProperty(BASE_URL_KEY,
            props.getProperty(LEGACY_OLLAMA_BASE_URL_KEY, defaultBaseUrl));
        String model = props.getProperty(MODEL_KEY,
            props.getProperty(LEGACY_OLLAMA_MODEL_KEY, defaultModel));
        LlmProvider provider = LlmProvider.parse(props.getProperty(PROVIDER_KEY));
        // If the user only has legacy keys, they explicitly pinned Ollama.
        if (!props.containsKey(PROVIDER_KEY)
            && (props.containsKey(LEGACY_OLLAMA_BASE_URL_KEY)
                || props.containsKey(LEGACY_OLLAMA_MODEL_KEY))) {
            provider = LlmProvider.OLLAMA;
        }
        String apiKey = envKey != null ? envKey : props.getProperty(API_KEY, defaultApiKey);
        return new Snapshot(
            provider,
            baseUrl,
            model,
            normalize(apiKey),
            normalize(props.getProperty(REMOTE_STANDARDS_URL_KEY))
        );
    }

    private void writeFile(Snapshot snap) throws IOException {
        Files.createDirectories(configFile.getParent());
        var props = new Properties();
        props.setProperty(PROVIDER_KEY, snap.provider().slug());
        props.setProperty(BASE_URL_KEY, snap.baseUrl());
        props.setProperty(MODEL_KEY, snap.model());
        if (snap.hasApiKey()) {
            props.setProperty(API_KEY, snap.apiKey());
        }
        if (snap.hasRemoteStandards()) {
            props.setProperty(REMOTE_STANDARDS_URL_KEY, snap.remoteStandardsUrl());
        }
        try (OutputStream out = Files.newOutputStream(configFile)) {
            props.store(out, "Launchpad user config");
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
