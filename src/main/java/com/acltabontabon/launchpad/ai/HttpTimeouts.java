package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Builds {@link RestClient.Builder} / {@link WebClient.Builder} instances pre-configured
 * with the connect+read timeouts from {@link LaunchpadAiProperties}. Shared by every
 * provider factory so no chat-model call can hang the TUI indefinitely.
 */
final class HttpTimeouts {

    private final LaunchpadAiProperties properties;

    HttpTimeouts(LaunchpadAiProperties properties) {
        this.properties = properties;
    }

    RestClient.Builder restClient() {
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(properties.readTimeout());
        // JdkClientHttpRequestFactory does not expose a connect timeout setter;
        // the read timeout bounds the entire request lifecycle (including a stuck connect).
        return RestClient.builder().requestFactory(factory);
    }

    WebClient.Builder webClient() {
        var httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
            .responseTimeout(properties.readTimeout());
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
