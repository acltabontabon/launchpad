package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.LaunchpadSettings.OllamaSettingsChanged;
import com.acltabontabon.launchpad.config.LaunchpadSettings.Snapshot;
import io.netty.channel.ChannelOption;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

/**
 * Wraps an {@link OllamaChatModel} so the underlying Ollama base URL and model
 * can be swapped at runtime without restarting the JVM. Spring AI's autoconfigured
 * {@code ChatClient.Builder} picks up this bean (via {@link Primary}) and wraps it,
 * so every {@code chatClient.prompt()...call()} routes through the current settings.
 * <p>
 * Every {@link OllamaApi} instance is built with explicit connect+read timeouts
 * from {@link LaunchpadAiProperties} so a hung daemon never freezes the TUI.
 */
@Component
@Primary
public class OllamaClientProvider implements ChatModel {

    private final LaunchpadAiProperties aiProperties;
    private volatile OllamaChatModel current;

    public OllamaClientProvider(LaunchpadSettings settings, LaunchpadAiProperties aiProperties) {
        this.aiProperties = aiProperties;
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

    private OllamaChatModel build(Snapshot snap) {
        var api = OllamaApi.builder()
            .baseUrl(snap.baseUrl())
            .restClientBuilder(timeoutAwareRestClient())
            .webClientBuilder(timeoutAwareWebClient())
            .build();
        var opts = OllamaChatOptions.builder().model(snap.model()).build();
        return OllamaChatModel.builder()
            .ollamaApi(api)
            .defaultOptions(opts)
            .build();
    }

    private RestClient.Builder timeoutAwareRestClient() {
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(aiProperties.readTimeout());
        // JdkClientHttpRequestFactory does not expose a connect timeout setter;
        // the underlying java.net.http.HttpClient defaults to no connect timeout.
        // The read timeout above still bounds a stuck connect because it covers
        // the entire request lifecycle.
        return RestClient.builder().requestFactory(factory);
    }

    private WebClient.Builder timeoutAwareWebClient() {
        var httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) aiProperties.connectTimeout().toMillis())
            .responseTimeout(aiProperties.readTimeout());
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
