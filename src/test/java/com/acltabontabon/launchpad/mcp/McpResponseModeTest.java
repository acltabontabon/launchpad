package com.acltabontabon.launchpad.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the explicit env &gt; property &gt; default precedence in
 * {@link McpResponseMode#resolve}. Kept free of Spring so the precedence is
 * verified in isolation, not via relaxed binding.
 */
class McpResponseModeTest {

    @Test
    void defaultsToReferencesWhenNothingSet() {
        assertThat(McpResponseMode.resolve(null, null)).isEqualTo(McpResponseMode.REFERENCES);
        assertThat(McpResponseMode.resolve("", "  ")).isEqualTo(McpResponseMode.REFERENCES);
    }

    @Test
    void propertyResolvesWhenEnvAbsent() {
        assertThat(McpResponseMode.resolve(null, "inline")).isEqualTo(McpResponseMode.INLINE);
        assertThat(McpResponseMode.resolve(null, "references")).isEqualTo(McpResponseMode.REFERENCES);
    }

    @Test
    void envBeatsProperty() {
        assertThat(McpResponseMode.resolve("inline", "references")).isEqualTo(McpResponseMode.INLINE);
        assertThat(McpResponseMode.resolve("references", "inline")).isEqualTo(McpResponseMode.REFERENCES);
    }

    @Test
    void parsingIsCaseInsensitiveAndTrimmed() {
        assertThat(McpResponseMode.resolve("  INLINE  ", null)).isEqualTo(McpResponseMode.INLINE);
        assertThat(McpResponseMode.resolve("References", null)).isEqualTo(McpResponseMode.REFERENCES);
    }

    @Test
    void unknownEnvFallsThroughToPropertyThenDefault() {
        // Unknown env, valid property -> property wins.
        assertThat(McpResponseMode.resolve("bogus", "inline")).isEqualTo(McpResponseMode.INLINE);
        // Unknown env and unknown property -> references default.
        assertThat(McpResponseMode.resolve("bogus", "nonsense")).isEqualTo(McpResponseMode.REFERENCES);
    }

    @Test
    void unrecognizedDetectionDistinguishesBlankFromTypo() {
        assertThat(McpResponseMode.isUnrecognized(null)).isFalse();
        assertThat(McpResponseMode.isUnrecognized("  ")).isFalse();
        assertThat(McpResponseMode.isUnrecognized("inline")).isFalse();
        assertThat(McpResponseMode.isUnrecognized("typo")).isTrue();
    }
}
