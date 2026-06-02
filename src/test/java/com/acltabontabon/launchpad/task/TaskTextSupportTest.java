package com.acltabontabon.launchpad.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the pure text-utility helpers in {@link TaskTextSupport}. */
class TaskTextSupportTest {

    @Nested
    class FirstSentence {

        @Test
        void doesNotSplitAtEgAbbreviation() {
            var input = "Every public API path or media type carries an explicit version "
                + "(e.g. `/v1/...` or `application/vnd.team.v1+json`). Breaking changes go to a new version.";
            var result = TaskTextSupport.firstSentence(input);
            assertThat(result).contains("e.g.");
            assertThat(result).contains("v1+json");
            assertThat(result).doesNotContain("Breaking changes");
        }

        @Test
        void splitsAtRealSentenceBoundary() {
            var result = TaskTextSupport.firstSentence(
                "First sentence here. Second sentence here.");
            assertThat(result).isEqualTo("First sentence here.");
        }

        @Test
        void returnsWholeStringWhenNoSentenceBoundary() {
            var result = TaskTextSupport.firstSentence("No periods just words");
            assertThat(result).isEqualTo("No periods just words");
        }

        @Test
        void handlesNullAndEmpty() {
            assertThat(TaskTextSupport.firstSentence(null)).isEmpty();
            assertThat(TaskTextSupport.firstSentence("")).isEmpty();
            assertThat(TaskTextSupport.firstSentence("   ")).isEmpty();
        }

        @Test
        void collapsesInternalWhitespaceAndNewlines() {
            var result = TaskTextSupport.firstSentence(
                "First   sentence\n  here.\n\nSecond sentence.");
            assertThat(result).isEqualTo("First sentence here.");
        }
    }
}
