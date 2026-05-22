package com.acltabontabon.launchpad.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.template.MergeMarkers;
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
        var existing = "# Hand-written CLAUDE.md\nLine.\n";
        var merged = MergeMarkers.mergeInto(existing, "generated content");
        assertThat(merged).startsWith("# Hand-written CLAUDE.md");
        assertThat(merged).contains("generated content");
        assertThat(MergeMarkers.hasMarkers(merged)).isTrue();
    }
}
