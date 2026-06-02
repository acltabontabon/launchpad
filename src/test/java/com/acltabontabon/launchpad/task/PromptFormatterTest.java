package com.acltabontabon.launchpad.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link PromptFormatter.PromptParts#split}. */
class PromptFormatterTest {

    @Nested
    class PromptPartsSplit {

        @Test
        void splitsOnMarkers() {
            var template = "===SYSTEM===\nsystem text\n===USER===\nuser text";
            var parts = PromptFormatter.PromptParts.split(template);
            assertThat(parts.system()).isEqualTo("system text");
            assertThat(parts.user()).isEqualTo("user text");
        }

        @Test
        void emptySystemWhenMarkersMissing() {
            var template = "just a body with no markers";
            var parts = PromptFormatter.PromptParts.split(template);
            assertThat(parts.system()).isEmpty();
            assertThat(parts.user()).isEqualTo(template);
        }

        @Test
        void emptySystemWhenOnlyUserMarker() {
            var template = "===USER===\nbody";
            var parts = PromptFormatter.PromptParts.split(template);
            assertThat(parts.system()).isEmpty();
            assertThat(parts.user()).isEqualTo(template);
        }

        @Test
        void handlesNull() {
            var parts = PromptFormatter.PromptParts.split(null);
            assertThat(parts.system()).isEmpty();
            assertThat(parts.user()).isEmpty();
        }
    }
}
