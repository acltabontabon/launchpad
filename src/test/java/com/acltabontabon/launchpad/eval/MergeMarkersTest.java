package com.acltabontabon.launchpad.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.launchpad.template.MergeMarkers;
import com.acltabontabon.launchpad.template.MergeMarkers.MarkerStatus;
import org.junit.jupiter.api.Test;

class MergeMarkersTest {

    @Test
    void wrapAddsMarkers() {
        var wrapped = MergeMarkers.wrap("hello");
        assertThat(wrapped).contains(MergeMarkers.START);
        assertThat(wrapped).contains(MergeMarkers.END);
        assertThat(MergeMarkers.hasMarkers(wrapped)).isTrue();
    }

    @Test
    void mergeReplacesManagedBlock() {
        var existing = "# User notes\n\n"
            + MergeMarkers.START + "\nold generated\n" + MergeMarkers.END
            + "\n\nMore user notes.";
        var merged = MergeMarkers.mergeInto(existing, "new generated");
        assertThat(merged).contains("# User notes");
        assertThat(merged).contains("More user notes.");
        assertThat(merged).contains("new generated");
        assertThat(merged).doesNotContain("old generated");
    }

    @Test
    void mergeAppendsBlockWhenAbsent() {
        var existing = "# Hand-written AGENTS.md\nLine.\n";
        var merged = MergeMarkers.mergeInto(existing, "generated content");
        assertThat(merged).startsWith("# Hand-written AGENTS.md");
        assertThat(merged).contains("generated content");
        assertThat(MergeMarkers.hasMarkers(merged)).isTrue();
    }

    @Test
    void classifyDetectsReversedOrder() {
        var content = "# user prelude\n"
            + MergeMarkers.END + "\nstray\n" + MergeMarkers.START
            + "\n# user trailer";
        assertThat(MergeMarkers.classify(content)).isEqualTo(MarkerStatus.CORRUPTED);
    }

    @Test
    void classifyDetectsDuplicateStart() {
        var content = MergeMarkers.START + "\none\n"
            + MergeMarkers.START + "\ntwo\n" + MergeMarkers.END;
        assertThat(MergeMarkers.classify(content)).isEqualTo(MarkerStatus.CORRUPTED);
    }

    @Test
    void classifyDetectsMissingEnd() {
        var content = "# user notes\n" + MergeMarkers.START + "\nopen block, never closed\n";
        assertThat(MergeMarkers.classify(content)).isEqualTo(MarkerStatus.CORRUPTED);
    }

    @Test
    void classifyDetectsMissingStart() {
        var content = "# user notes\nfree text\n" + MergeMarkers.END + "\nmore\n";
        assertThat(MergeMarkers.classify(content)).isEqualTo(MarkerStatus.CORRUPTED);
    }

    @Test
    void classifyReportsValidForWellFormedBlock() {
        var content = "prefix\n" + MergeMarkers.START + "\nx\n" + MergeMarkers.END + "\nsuffix";
        assertThat(MergeMarkers.classify(content)).isEqualTo(MarkerStatus.VALID);
    }

    @Test
    void classifyReportsNoneWhenNoMarkers() {
        assertThat(MergeMarkers.classify("just text")).isEqualTo(MarkerStatus.NONE);
    }

    @Test
    void mergeIntoRefusesCorruptedContent() {
        var corrupted = "user content\n" + MergeMarkers.END + "\n" + MergeMarkers.START + "\n";
        assertThatThrownBy(() -> MergeMarkers.mergeInto(corrupted, "new"))
            .isInstanceOf(IllegalStateException.class);
    }
}
