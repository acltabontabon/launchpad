package com.acltabontabon.launchpad.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class LaunchpadSettings {

    public record Snapshot(String baseUrl, String model) {}

    public record OllamaSettingsChanged(Snapshot snapshot) {}

    private static final String BASE_URL_KEY = "spring.ai.ollama.base-url";
    private static final String MODEL_KEY = "spring.ai.ollama.chat.options.model";

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

    public void update(String baseUrl, String model) throws IOException {
        var next = new Snapshot(baseUrl, model);
        writeFile(next);
        current.set(next);
        events.publishEvent(new OllamaSettingsChanged(next));
    }

    private Snapshot loadOrDefault(String defaultBaseUrl, String defaultModel) {
        if (!Files.isRegularFile(configFile)) {
            return new Snapshot(defaultBaseUrl, defaultModel);
        }
        var props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            return new Snapshot(defaultBaseUrl, defaultModel);
        }
        return new Snapshot(
            props.getProperty(BASE_URL_KEY, defaultBaseUrl),
            props.getProperty(MODEL_KEY, defaultModel)
        );
    }

    private void writeFile(Snapshot snap) throws IOException {
        Files.createDirectories(configFile.getParent());
        var props = new Properties();
        props.setProperty(BASE_URL_KEY, snap.baseUrl());
        props.setProperty(MODEL_KEY, snap.model());
        try (OutputStream out = Files.newOutputStream(configFile)) {
            props.store(out, "Launchpad user config");
        }
    }
}
