package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.LaunchpadSettings.LlmProviderSettingsChanged;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Single {@link ChatModel} the rest of Launchpad sees. Holds a volatile delegate
 * that points at either the Ollama- or OpenAI-compatible-backed model, depending
 * on the current {@link LaunchpadSettings} snapshot. Settings changes published
 * through {@link LlmProviderSettingsChanged} swap the delegate in place so the
 * user can switch providers without restarting the JVM.
 * <p>
 * The Spring AI autoconfigured {@code ChatClient.Builder} picks up this bean
 * (via {@link Primary}) and wraps it, so every {@code chatClient.prompt()...call()}
 * routes through the active provider.
 */
@Component
@Primary
public class LlmProviderRouter implements ChatModel {

    private final OllamaChatModelFactory ollamaFactory;
    private final OpenAiCompatibleChatModelFactory openAiFactory;
    private final ProviderHealthChecker healthChecker;
    private volatile ChatModel delegate;

    public LlmProviderRouter(LaunchpadSettings settings,
                             LaunchpadAiProperties properties,
                             ProviderHealthChecker healthChecker) {
        this.ollamaFactory = new OllamaChatModelFactory(properties);
        this.openAiFactory = new OpenAiCompatibleChatModelFactory(properties);
        this.healthChecker = healthChecker;
        this.delegate = build(settings.snapshot());
    }

    @EventListener
    public void onSettingsChanged(LlmProviderSettingsChanged event) {
        this.delegate = build(event.snapshot());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private ChatModel build(Snapshot snap) {
        var concrete = snap.provider() == LlmProvider.AUTO
            ? healthChecker.resolveAuto(snap.baseUrl(), snap.apiKey())
            : snap.provider();
        return switch (concrete) {
            case OLLAMA -> ollamaFactory.build(snap);
            case OPENAI_COMPATIBLE -> openAiFactory.build(snap);
            // resolveAuto() never returns AUTO; the fallthrough keeps the
            // switch exhaustive and routes to Ollama as a last-resort default.
            case AUTO -> ollamaFactory.build(snap);
        };
    }
}
