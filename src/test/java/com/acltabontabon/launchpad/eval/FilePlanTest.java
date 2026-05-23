package com.acltabontabon.launchpad.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.template.FilePlan;
import com.acltabontabon.launchpad.template.GeneratedFile;
import com.acltabontabon.launchpad.template.MergeMarkers;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePlanTest {

    @Test
    void newFileGetsWriteNewAction(@TempDir Path root) {
        var file = new GeneratedFile("CLAUDE.md", "# generated", GeneratedFile.FileKind.CONTEXT);
        var plan = FilePlan.compute(file, root);
        assertThat(plan.action()).isEqualTo(FilePlan.Action.WRITE_NEW);
        assertThat(plan.exists).isFalse();
    }

    @Test
    void existingFileWithoutMarkersDefaultsToSkip(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("CLAUDE.md"), "# user hand-wrote this");
        var file = new GeneratedFile("CLAUDE.md", "# generated", GeneratedFile.FileKind.CONTEXT);
        var plan = FilePlan.compute(file, root);
        assertThat(plan.action()).isEqualTo(FilePlan.Action.SKIP);
        assertThat(plan.hasMarkers).isFalse();
    }

    @Test
    void corruptedMarkersResolveToCorruptedActionAndDoNotMutateContent(@TempDir Path root) throws Exception {
        var prelude = "# Important user notes\nLine A\nLine B\n";
        var corrupted = prelude
            + MergeMarkers.END + "\nstray\n" + MergeMarkers.START + "\nblock\n";
        Files.writeString(root.resolve("CLAUDE.md"), corrupted);
        var file = new GeneratedFile("CLAUDE.md", "# generated", GeneratedFile.FileKind.CONTEXT);
        var plan = FilePlan.compute(file, root);
        assertThat(plan.action()).isEqualTo(FilePlan.Action.CORRUPTED);
        assertThat(plan.hasMarkers).isFalse();
        assertThat(plan.statusChip()).isEqualTo("CORRUPTED");
        assertThat(plan.resolvedContent()).isEqualTo(corrupted);
    }

    @Test
    void existingFileWithMarkersDefaultsToMerge(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("CLAUDE.md"),
            "# user prelude\n\n" + MergeMarkers.START + "\nold\n" + MergeMarkers.END + "\n");
        var file = new GeneratedFile("CLAUDE.md", "fresh", GeneratedFile.FileKind.CONTEXT);
        var plan = FilePlan.compute(file, root);
        assertThat(plan.action()).isEqualTo(FilePlan.Action.MERGE);
        assertThat(plan.resolvedContent()).contains("# user prelude");
        assertThat(plan.resolvedContent()).contains("fresh");
        assertThat(plan.resolvedContent()).doesNotContain("\nold\n");
    }
}
