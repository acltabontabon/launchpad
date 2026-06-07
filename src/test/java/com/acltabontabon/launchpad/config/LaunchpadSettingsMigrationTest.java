package com.acltabontabon.launchpad.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.ai.LlmProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Backward-compat probe. A pre-0.2 user config that only carries the legacy
 * {@code spring.ai.ollama.*} keys must still resolve to a usable snapshot
 * pinned to {@link LlmProvider#OLLAMA}, with the legacy values intact. On the
 * next save, the file is rewritten with the new provider-neutral schema.
 */
class LaunchpadSettingsMigrationTest {

    private Path tempHome;
    private String originalHome;

    @BeforeEach
    void redirectHome() throws IOException {
        tempHome = Files.createTempDirectory("launchpad-settings-test");
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreHome() throws IOException {
        System.setProperty("user.home", originalHome);
        deleteRecursively(tempHome);
    }

    @Test
    void readsLegacyOllamaKeysWhenNewKeysAbsent() throws IOException {
        var configDir = tempHome.resolve(".launchpad");
        Files.createDirectories(configDir);
        var legacy = new Properties();
        legacy.setProperty("spring.ai.ollama.base-url", "http://legacy:11434");
        legacy.setProperty("spring.ai.ollama.chat.options.model", "legacy-model:7b");
        try (var out = Files.newOutputStream(configDir.resolve("config.properties"))) {
            legacy.store(out, null);
        }

        var settings = newSettings();
        var snap = settings.snapshot();

        assertThat(snap.provider()).isEqualTo(LlmProvider.OLLAMA);
        assertThat(snap.baseUrl()).isEqualTo("http://legacy:11434");
        assertThat(snap.model()).isEqualTo("legacy-model:7b");
        assertThat(snap.hasApiKey()).isFalse();
    }

    @Test
    void rewriteOnSavePersistsNewSchema() throws IOException {
        var settings = newSettings();
        settings.update(LlmProvider.OPENAI_COMPATIBLE,
            "http://localhost:1234/v1", "local-model", "", null);

        var written = new Properties();
        try (var in = Files.newInputStream(tempHome.resolve(".launchpad").resolve("config.properties"))) {
            written.load(in);
        }
        assertThat(written.getProperty("launchpad.ai.provider")).isEqualTo("openai-compatible");
        assertThat(written.getProperty("launchpad.ai.base-url")).isEqualTo("http://localhost:1234/v1");
        assertThat(written.getProperty("launchpad.ai.model")).isEqualTo("local-model");
        // Empty api key is dropped, not written as an empty value.
        assertThat(written.getProperty("launchpad.ai.api-key")).isNull();
    }

    @Test
    void auditLlmChecksDefaultsOffAndRoundTrips() throws IOException {
        var settings = newSettings();
        assertThat(settings.snapshot().enableLlmChecks()).isFalse();

        settings.updateAuditFlags(true);
        assertThat(settings.snapshot().enableLlmChecks()).isTrue();

        var written = new Properties();
        try (var in = Files.newInputStream(tempHome.resolve(".launchpad").resolve("config.properties"))) {
            written.load(in);
        }
        assertThat(written.getProperty("launchpad.audit.enable-llm-checks")).isEqualTo("true");

        // A fresh LaunchpadSettings instance must re-hydrate the flag from disk.
        var reloaded = newSettings();
        assertThat(reloaded.snapshot().enableLlmChecks()).isTrue();
    }

    @Test
    void parseFallsBackToAutoOnUnknownProviderSlug() {
        assertThat(LlmProvider.parse(null)).isEqualTo(LlmProvider.AUTO);
        assertThat(LlmProvider.parse("")).isEqualTo(LlmProvider.AUTO);
        assertThat(LlmProvider.parse("nonsense")).isEqualTo(LlmProvider.AUTO);
        assertThat(LlmProvider.parse("ollama")).isEqualTo(LlmProvider.OLLAMA);
        assertThat(LlmProvider.parse("openai-compatible")).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
    }

    private LaunchpadSettings newSettings() {
        return new LaunchpadSettings(
            "auto",
            "http://localhost:11434",
            "qwen2.5-coder:7b-instruct",
            "",
            false,
            new NoOpEvents());
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
        }
    }

    private static final class NoOpEvents implements ApplicationEventPublisher {
        @Override public void publishEvent(Object event) {}
    }
}
