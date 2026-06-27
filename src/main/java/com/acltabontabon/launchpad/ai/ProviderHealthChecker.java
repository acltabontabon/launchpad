package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import org.springframework.stereotype.Component;

/**
 * Owns provider resolution policy and is the only place that probes the network
 * during resolution. Dispatches to the {@link PreparationProvider} beans held by
 * {@link ProviderRegistry}; there is no {@code switch} over {@link LlmProvider}.
 *
 * <p>Resolution rules, shared by {@link #resolve(Snapshot)} (which model to
 * build) and {@link #check()} (what status to display):
 * <ul>
 *   <li>synthesis disabled globally -> the deterministic (no-AI) provider,
 *       regardless of the configured provider;</li>
 *   <li>a pinned provider -> that provider;</li>
 *   <li>{@link LlmProvider#AUTO} -> probe the auto-detectable providers in order
 *       and take the first that is reachable. {@code MODEL_MISSING} counts as
 *       reachable (the daemon answered); only {@code DAEMON_DOWN} is skipped.</li>
 * </ul>
 */
@Component
public class ProviderHealthChecker {

    private final LaunchpadSettings settings;
    private final LaunchpadAiProperties properties;
    private final ProviderRegistry registry;

    public ProviderHealthChecker(LaunchpadSettings settings,
                                 LaunchpadAiProperties properties,
                                 ProviderRegistry registry) {
        this.settings = settings;
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * Resolve the provider whose chat model the router should build for this
     * snapshot. Concrete providers resolve without any network call; only the
     * AUTO path probes.
     */
    public PreparationProvider resolve(Snapshot snap) {
        if (!properties.synthesis().enabled()) {
            return registry.deterministic();
        }
        if (snap.provider() == LlmProvider.AUTO) {
            return firstReachableOrFallback(snap);
        }
        var provider = registry.get(snap.provider().slug());
        return provider != null ? provider : registry.deterministic();
    }

    /** Health status for the currently configured provider. */
    public LlmProviderStatus check() {
        var snap = settings.snapshot();
        if (!properties.synthesis().enabled()) {
            return registry.deterministic().check(snap);
        }
        if (snap.provider() != LlmProvider.AUTO) {
            var provider = registry.get(snap.provider().slug());
            return provider != null
                ? provider.check(snap)
                : LlmProviderStatus.daemonDown(snap.provider(), snap.baseUrl());
        }
        // AUTO: probe ordered auto-detectable providers; first reachable wins.
        for (var provider : registry.autoDetectable()) {
            var status = provider.check(snap);
            if (status.state() != LlmProviderStatus.State.DAEMON_DOWN) {
                return status;
            }
        }
        return LlmProviderStatus.daemonDown(LlmProvider.AUTO, snap.baseUrl());
    }

    /**
     * First auto-detectable provider that answers a probe; if none answer, the
     * first auto-detectable provider (so a daemon-down badge still names an
     * actionable backend rather than nothing).
     */
    private PreparationProvider firstReachableOrFallback(Snapshot snap) {
        var candidates = registry.autoDetectable();
        for (var provider : candidates) {
            if (provider.check(snap).state() != LlmProviderStatus.State.DAEMON_DOWN) {
                return provider;
            }
        }
        return candidates.isEmpty() ? registry.deterministic() : candidates.get(0);
    }
}
