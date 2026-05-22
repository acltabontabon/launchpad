package com.acltabontabon.launchpad.config;

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

    public record Snapshot(String baseUrl, String model, String remoteStandardsUrl) {
        public boolean hasRemoteStandards() {
            return remoteStandardsUrl != null && !remoteStandardsUrl.isBlank();
        }
    }

    public record OllamaSettingsChanged(Snapshot snapshot) {}

    public record RemoteStandardsSettingsChanged(Snapshot snapshot) {}

    private static final String BASE_URL_KEY = "spring.ai.ollama.base-url";
    private static final String MODEL_KEY = "spring.ai.ollama.chat.options.model";
    private static final String REMOTE_STANDARDS_URL_KEY = "launchpad.standards.remote.url";

    private final Path configFile = Path.of(System.getProperty("user.home"), ".launchpad", "config.properties");
    private final AtomicReference<Snapshot> current;
    private final ApplicationEventPublisher events;

    public LaunchpadSettings(
        @Value("${spring.ai.ollama.base-url}") String defaultBaseUrl,
        @Value("${spring.ai.ollama.chat.options.model}") String defaultModel,
        ApplicationEventPublisher events
    ) {
        this.events = events;
        this.current = new AtomicReference<>(loadOrDefault(defaultBaseUrl, defaultModel));
    }

    public Snapshot snapshot() {
        return current.get();
    }

    public void update(String baseUrl, String model, String remoteStandardsUrl) throws IOException {
        var previous = current.get();
        var next = new Snapshot(baseUrl, model, normalize(remoteStandardsUrl));
        writeFile(next);
        current.set(next);
        if (!Objects.equals(previous.baseUrl(), next.baseUrl())
            || !Objects.equals(previous.model(), next.model())) {
            events.publishEvent(new OllamaSettingsChanged(next));
        }
        if (!Objects.equals(previous.remoteStandardsUrl(), next.remoteStandardsUrl())) {
            events.publishEvent(new RemoteStandardsSettingsChanged(next));
        }
    }

    private Snapshot loadOrDefault(String defaultBaseUrl, String defaultModel) {
        if (!Files.isRegularFile(configFile)) {
            return new Snapshot(defaultBaseUrl, defaultModel, null);
        }
        var props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            return new Snapshot(defaultBaseUrl, defaultModel, null);
        }
        return new Snapshot(
            props.getProperty(BASE_URL_KEY, defaultBaseUrl),
            props.getProperty(MODEL_KEY, defaultModel),
            normalize(props.getProperty(REMOTE_STANDARDS_URL_KEY))
        );
    }

    private void writeFile(Snapshot snap) throws IOException {
        Files.createDirectories(configFile.getParent());
        var props = new Properties();
        props.setProperty(BASE_URL_KEY, snap.baseUrl());
        props.setProperty(MODEL_KEY, snap.model());
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
