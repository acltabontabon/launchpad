package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.LaunchpadSettings.OllamaSettingsChanged;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Wraps an {@link OllamaChatModel} so the underlying Ollama base URL and model
 * can be swapped at runtime without restarting the JVM. Spring AI's autoconfigured
 * {@code ChatClient.Builder} picks up this bean (via {@link Primary}) and wraps it,
 * so every {@code chatClient.prompt()...call()} routes through the current settings.
 */
@Component
@Primary
public class OllamaClientProvider implements ChatModel {

    private volatile OllamaChatModel current;

    public OllamaClientProvider(LaunchpadSettings settings) {
        this.current = build(settings.snapshot());
    }

    @EventListener
    public void onSettingsChanged(OllamaSettingsChanged event) {
        this.current = build(event.snapshot());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return current.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return current.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return current.getDefaultOptions();
    }

    private static OllamaChatModel build(Snapshot snap) {
        var api = OllamaApi.builder().baseUrl(snap.baseUrl()).build();
        var opts = OllamaChatOptions.builder().model(snap.model()).build();
        return OllamaChatModel.builder()
            .ollamaApi(api)
            .defaultOptions(opts)
            .build();
    }
}
