package com.acltabontabon.launchpad.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds every call into the local Ollama daemon so a stalled model or a
 * hung network does not freeze the TUI thread. Two-axis budget:
 * <ul>
 *   <li>{@code connectTimeout} - TCP / HTTP connect phase. Tight by default
 *       because a non-running daemon should surface in seconds, not minutes.</li>
 *   <li>{@code readTimeout} - upper bound on the entire response. Applied at
 *       both the HTTP client layer (covers the synchronous {@code .call()} path
 *       used by the interview) and as a Reactor {@code .timeout()} on the
 *       streamed generation path.</li>
 * </ul>
 */
@ConfigurationProperties("launchpad.ai")
public record LaunchpadAiProperties(Duration connectTimeout, Duration readTimeout) {

    public LaunchpadAiProperties {
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(10);
        if (readTimeout == null) readTimeout = Duration.ofMinutes(2);
    }
}
