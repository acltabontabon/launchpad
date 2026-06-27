package com.acltabontabon.launchpad.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The registry is a pure lookup table: it resolves providers by id, exposes the
 * deterministic provider, and lists auto-detectable providers in declared order.
 * It performs no network calls - resolution policy and probing live in
 * {@link ProviderHealthChecker}.
 */
class ProviderRegistryTest {

    private ProviderRegistry newRegistry() {
        var props = LaunchpadAiProperties.ofTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));
        var probe = new ProviderProbe();
        return new ProviderRegistry(List.of(
            new OllamaProvider(props, probe),
            new OpenAiCompatibleProvider(props, probe),
            new DeterministicProvider()));
    }

    @Test
    void resolvesProvidersById() {
        var registry = newRegistry();

        assertThat(registry.get(LlmProvider.OLLAMA.slug())).isInstanceOf(OllamaProvider.class);
        assertThat(registry.get(LlmProvider.OPENAI_COMPATIBLE.slug()))
            .isInstanceOf(OpenAiCompatibleProvider.class);
        assertThat(registry.get("nope")).isNull();
    }

    @Test
    void exposesDeterministicProvider() {
        assertThat(newRegistry().deterministic()).isInstanceOf(DeterministicProvider.class);
    }

    @Test
    void autoDetectableExcludesDeterministicAndKeepsOrder() {
        var ids = newRegistry().autoDetectable().stream().map(PreparationProvider::id).toList();

        assertThat(ids).containsExactly(
            LlmProvider.OLLAMA.slug(), LlmProvider.OPENAI_COMPATIBLE.slug());
    }

    @Test
    void allReturnsEveryProviderInDeclaredOrder() {
        var ids = newRegistry().all().stream().map(PreparationProvider::id).toList();

        assertThat(ids).containsExactly(
            LlmProvider.OLLAMA.slug(),
            LlmProvider.OPENAI_COMPATIBLE.slug(),
            LlmProvider.DETERMINISTIC.slug());
    }
}
