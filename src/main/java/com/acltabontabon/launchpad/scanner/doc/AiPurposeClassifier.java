package com.acltabontabon.launchpad.scanner.doc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Opt-in local-AI fallback used by {@link PurposeClassifier} when the
 * deterministic heuristics return {@link Purpose#UNKNOWN}. Sends a small slice
 * of the file (path + content prefix) to the local model and constrains the
 * answer with Spring AI structured output - the model returns a single
 * {@link Purpose} enum value.
 * <p>
 * <b>Default: disabled.</b> Gated by {@code launchpad.scan.doc-ai-classify}
 * (default {@code false}). When disabled, {@link #classify} short-circuits
 * without touching the {@link ChatClient}, which means the default scan path
 * stays fully offline. We deliberately keep this bean unconditional (rather
 * than {@code @ConditionalOnProperty}) so it survives Spring AOT pruning under
 * the native build: enabling the flag at runtime then takes effect without a
 * native rebuild. Pattern mirrors {@code LlmChecker}'s reasoning, documented
 * inline there.
 * <p>
 * On any failure (Ollama down, timeout, parse error, no ChatClient bean
 * available at construction time) the classifier returns {@link Purpose#UNKNOWN}
 * - never throws. Logged at DEBUG so an offline scan stays quiet.
 */
@Component
public class AiPurposeClassifier {

    private static final Logger log = LoggerFactory.getLogger(AiPurposeClassifier.class);

    /** Cap the file slice we ship to the model. Large enough for headings + first paragraph. */
    private static final int CONTENT_PREFIX_CHARS = 4_000;

    private final ChatClient chatClient;
    private final boolean enabled;

    public AiPurposeClassifier(
        ChatClient.Builder builder,
        @Value("${launchpad.scan.doc-ai-classify:false}") boolean enabled
    ) {
        this.chatClient = builder == null ? null : builder.build();
        this.enabled = enabled;
    }

    /** Whether the property opt-in flag is set. PurposeClassifier checks this before calling. */
    public boolean isEnabled() {
        return enabled && chatClient != null;
    }

    /**
     * Ask the local model to classify a single page. {@code content} may be
     * null or empty; the model still gets the path, which carries most of the
     * signal in practice. Always returns a non-null {@link Purpose} - never
     * throws.
     */
    public Purpose classify(String relativePath, String content) {
        if (!isEnabled()) return Purpose.UNKNOWN;
        String slice = content == null ? "" :
            content.length() > CONTENT_PREFIX_CHARS ? content.substring(0, CONTENT_PREFIX_CHARS) : content;
        var prompt = """
            Classify the documentation file below into exactly one purpose from
            this set: OVERVIEW, SETUP, ARCHITECTURE, API, OPERATIONS,
            CONTRIBUTION, CHANGELOG, UNKNOWN. Reply with the enum name only.

            PATH: %s
            CONTENT PREFIX:
            ```
            %s
            ```
            """.formatted(relativePath, slice);
        try {
            Purpose result = chatClient.prompt()
                .user(prompt)
                .call()
                .entity(Purpose.class);
            return result == null ? Purpose.UNKNOWN : result;
        } catch (RuntimeException e) {
            log.debug("AI purpose classification failed for {} - falling back to UNKNOWN ({})",
                relativePath, e.getMessage());
            return Purpose.UNKNOWN;
        }
    }
}
