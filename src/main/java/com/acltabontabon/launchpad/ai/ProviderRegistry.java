package com.acltabontabon.launchpad.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Pure registry of {@link PreparationProvider} beans. Collects every provider on
 * the classpath and exposes them by id, as the deterministic (no-AI) provider,
 * and as the ordered list of auto-detectable providers.
 *
 * <p>This is a lookup table only: it performs no network calls and holds no
 * resolution policy. AUTO probing and the synthesis-disabled fallback live in
 * {@link ProviderHealthChecker}.
 *
 * <p>Spring injects {@code List<PreparationProvider>} in {@code @Order}, so the
 * iteration order is deterministic (Ollama, then OpenAI-compatible, then the
 * deterministic provider).
 */
@Component
public class ProviderRegistry {

    private final Map<String, PreparationProvider> byId;
    private final List<PreparationProvider> ordered;
    private final List<PreparationProvider> autoDetectable;
    private final PreparationProvider deterministic;

    public ProviderRegistry(List<PreparationProvider> providers) {
        var map = new LinkedHashMap<String, PreparationProvider>();
        for (var provider : providers) {
            map.put(provider.id(), provider);
        }
        this.byId = Map.copyOf(map);
        this.ordered = List.copyOf(providers);
        this.autoDetectable = providers.stream().filter(PreparationProvider::autoDetectable).toList();
        this.deterministic = byId.get(LlmProvider.DETERMINISTIC.slug());
    }

    /** The provider registered under {@code id}, or {@code null} when none matches. No probing. */
    public PreparationProvider get(String id) {
        return byId.get(id);
    }

    /** The deterministic (no-AI) provider. Always present. */
    public PreparationProvider deterministic() {
        return deterministic;
    }

    /** Providers AUTO may resolve to, in priority order. Excludes the deterministic provider. */
    public List<PreparationProvider> autoDetectable() {
        return autoDetectable;
    }

    /** Every registered provider, in declared order. Used for TUI rendering. */
    public List<PreparationProvider> all() {
        return ordered;
    }
}
