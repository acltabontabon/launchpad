package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import org.springframework.ai.chat.model.ChatModel;

/**
 * A pluggable preparation backend. Each provider knows how to build a
 * {@link ChatModel} for a settings snapshot and how to report its own health.
 * Beans are auto-collected into {@link ProviderRegistry}, so adding a provider
 * is a new bean rather than an edit to the router or the health checker.
 *
 * <p>Mirrors the {@code audit.RuleChecker} SPI already used in this codebase:
 * an {@link #id()} discriminator plus a Spring-collected
 * {@code List<PreparationProvider>} resolved into a map by id.
 */
public interface PreparationProvider {

    /** Stable provider key. Matches the corresponding {@link LlmProvider#slug()}. */
    String id();

    /** Build the chat model this provider backs for the given settings snapshot. */
    ChatModel build(Snapshot snap);

    /** Probe this provider's health for the given settings snapshot. */
    LlmProviderStatus check(Snapshot snap);

    /**
     * Whether {@link LlmProvider#AUTO} resolution may select this provider by
     * probing. Network-backed providers return {@code true}; the deterministic
     * (no-AI) provider returns {@code false} so AUTO never silently lands on it.
     */
    default boolean autoDetectable() {
        return true;
    }
}
