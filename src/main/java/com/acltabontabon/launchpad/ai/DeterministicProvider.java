package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * First-class no-AI provider. Selecting it (or disabling synthesis globally)
 * produces deterministic-only output with no network calls: {@link #check}
 * always reports ready without probing anything.
 *
 * <p>{@link #build} returns a no-op {@link ChatModel} purely as a safety net.
 * The runtime contract is {@code aiAvailable == false} whenever this provider is
 * active (see {@code LaunchpadRunner}), so the synthesis layer short-circuits to
 * its deterministic fallback before this model is ever invoked.
 */
@Component
@Order(100)
public class DeterministicProvider implements PreparationProvider {

    @Override
    public String id() {
        return LlmProvider.DETERMINISTIC.slug();
    }

    @Override
    public ChatModel build(Snapshot snap) {
        return new NoOpChatModel();
    }

    @Override
    public LlmProviderStatus check(Snapshot snap) {
        return LlmProviderStatus.deterministic();
    }

    /** Excluded from AUTO probing - AUTO should never silently land on no-AI mode. */
    @Override
    public boolean autoDetectable() {
        return false;
    }

    /**
     * Inert {@link ChatModel} that yields no content. Never reached on the normal
     * path; exists so a stray call cannot blow up an assembling document.
     */
    private static final class NoOpChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.empty();
        }
    }
}
