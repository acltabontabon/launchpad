package com.acltabontabon.launchpad.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.LaunchpadSettings.LlmProviderSettingsChanged;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

/**
 * Verifies the router builds the right concrete {@link org.springframework.ai.chat.model.ChatModel}
 * for each provider and rebuilds it on a {@link LlmProviderSettingsChanged}
 * event. We never invoke the underlying chat models (they would call out to a
 * non-existent local server); a delegate-class assertion is enough.
 */
class LlmProviderRouterTest {

    @Test
    void buildsOllamaDelegateWhenProviderIsOllama() {
        var router = newRouter(new Snapshot(
            LlmProvider.OLLAMA, "http://localhost:11434", "qwen2.5-coder:7b", null, null, null));

        assertThat(delegateOf(router)).isInstanceOf(OllamaChatModel.class);
    }

    @Test
    void buildsOpenAiDelegateWhenProviderIsOpenAiCompatible() {
        var router = newRouter(new Snapshot(
            LlmProvider.OPENAI_COMPATIBLE, "http://localhost:1234/v1", "local-model", null, null, null));

        assertThat(delegateOf(router)).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void swapsDelegateWhenSettingsEventFires() {
        var router = newRouter(new Snapshot(
            LlmProvider.OLLAMA, "http://localhost:11434", "qwen2.5-coder:7b", null, null, null));
        assertThat(delegateOf(router)).isInstanceOf(OllamaChatModel.class);

        router.onSettingsChanged(new LlmProviderSettingsChanged(new Snapshot(
            LlmProvider.OPENAI_COMPATIBLE, "http://localhost:1234/v1", "local-model", null, null, null)));

        assertThat(delegateOf(router)).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void delegatesGetDefaultOptionsToActiveModel() {
        var router = newRouter(new Snapshot(
            LlmProvider.OLLAMA, "http://localhost:11434", "qwen2.5-coder:7b", null, null, null));

        // Should not throw and should return some non-null options from the delegate.
        assertThat(router.getDefaultOptions()).isNotNull();
    }

    private static LlmProviderRouter newRouter(Snapshot snapshot) {
        var settings = new StubSettings(snapshot);
        var properties = LaunchpadAiProperties.ofTimeouts(Duration.ofMillis(500), Duration.ofMillis(500));
        var healthChecker = new ProviderHealthChecker(settings);
        return new LlmProviderRouter(settings, properties, healthChecker);
    }

    /**
     * Reach into the router's volatile delegate field via reflection so the
     * test can assert which concrete model is active without exposing the
     * field to production code.
     */
    private static Object delegateOf(LlmProviderRouter router) {
        try {
            var field = LlmProviderRouter.class.getDeclaredField("delegate");
            field.setAccessible(true);
            return field.get(router);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class StubSettings extends LaunchpadSettings {
        private final AtomicReference<Snapshot> snap;
        StubSettings(Snapshot snap) {
            super("auto", "http://unused", "unused", "", event -> {});
            this.snap = new AtomicReference<>(snap);
        }
        @Override public Snapshot snapshot() { return snap.get(); }
    }
}
