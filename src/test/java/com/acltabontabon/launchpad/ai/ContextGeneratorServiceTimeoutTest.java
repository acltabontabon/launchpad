package com.acltabontabon.launchpad.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.scanner.PackageSummary;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Regression for issue #1: a stalled Ollama stream must not block the calling
 * thread indefinitely. With the {@code .timeout(readTimeout)} on the streaming
 * chain, a {@link Flux#never()} response surfaces a {@link TimeoutException}
 * within the configured budget rather than hanging on {@code blockLast()}.
 */
class ContextGeneratorServiceTimeoutTest {

    @Test
    void stalledStreamSurfacesTimeoutWithinBudget() {
        var properties = new LaunchpadAiProperties(Duration.ofMillis(50), Duration.ofMillis(150));
        var hangingModel = new HangingChatModel();
        var service = new ContextGeneratorService(
            ChatClient.builder(hangingModel),
            new PromptSelector(new FacetPromptComposer()),
            properties);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> service.generateProjectSummary(genericContext(), chunk -> {}))
            .hasRootCauseInstanceOf(TimeoutException.class);
        long elapsed = System.currentTimeMillis() - start;

        // The timeout budget is 150 ms; allow generous headroom for CI but
        // confirm the call did NOT hang (would otherwise be many seconds).
        if (elapsed > 5_000) {
            throw new AssertionError("timeout did not fire promptly, elapsed=" + elapsed + "ms");
        }
    }

    private static ProjectContext genericContext() {
        return new ProjectContext(
            "demo", "/tmp/demo",
            new StackProfile("Java", "Maven", null, List.of()),
            List.of("Main.java"), List.of(), Map.of(),
            List.of(new Dependency("x", "1", "runtime")),
            Map.of(), List.<PackageSummary>of(), null);
    }

    /** ChatModel whose streaming response never emits, simulating a stalled Ollama. */
    private static final class HangingChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of());
        }
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.never();
        }
        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }
}
