package com.acltabontabon.launchpad.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.template.FilePlan;
import com.acltabontabon.launchpad.template.GeneratedFile;
import com.acltabontabon.launchpad.template.MergeMarkers;
import com.acltabontabon.launchpad.template.WriteService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteServiceTest {

    private final WriteService writer = new WriteService();

    @Test
    void writesNewFilesWithoutBackup(@TempDir Path root) throws Exception {
        var file = new GeneratedFile("AGENTS.md", "hello", GeneratedFile.FileKind.CONTEXT);
        var plan = FilePlan.compute(file, root);
        var result = writer.apply(root, List.of(plan));

        assertThat(result.written()).isEqualTo(1);
        assertThat(result.backedUp()).isZero();
        assertThat(result.backupDir()).isNull();
        assertThat(Files.readString(root.resolve("AGENTS.md"))).isEqualTo("hello");
    }

    @Test
    void backsUpExistingFilesBeforeOverwriting(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "OLD CONTENT");
        var file = new GeneratedFile("AGENTS.md", "NEW CONTENT", GeneratedFile.FileKind.CONTEXT);
        var plan = FilePlan.compute(file, root);
        plan.setAction(FilePlan.Action.OVERWRITE);

        var result = writer.apply(root, List.of(plan));

        assertThat(result.written()).isEqualTo(1);
        assertThat(result.backedUp()).isEqualTo(1);
        assertThat(result.backupDir()).isNotNull();
        assertThat(Files.readString(root.resolve("AGENTS.md"))).isEqualTo("NEW CONTENT");
        assertThat(Files.readString(result.backupDir().resolve("AGENTS.md"))).isEqualTo("OLD CONTENT");
    }

    @Test
    void skipActionDoesNotTouchTarget(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "USER OWNED");
        var file = new GeneratedFile("AGENTS.md", "GENERATED", GeneratedFile.FileKind.CONTEXT);
        var plan = FilePlan.compute(file, root);
        // default for existing-no-markers is SKIP

        var result = writer.apply(root, List.of(plan));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.written()).isZero();
        assertThat(Files.readString(root.resolve("AGENTS.md"))).isEqualTo("USER OWNED");
    }

    @Test
    void mergeReplacesManagedBlockOnly(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"),
            "user prelude\n" + MergeMarkers.START + "\nOLD MANAGED\n" + MergeMarkers.END + "\ntrailing notes\n");
        var file = new GeneratedFile("AGENTS.md", "NEW MANAGED", GeneratedFile.FileKind.CONTEXT);
        var plan = FilePlan.compute(file, root);
        // markers present so default is MERGE

        writer.apply(root, List.of(plan));

        var saved = Files.readString(root.resolve("AGENTS.md"));
        assertThat(saved).contains("user prelude");
        assertThat(saved).contains("trailing notes");
        assertThat(saved).contains("NEW MANAGED");
        assertThat(saved).doesNotContain("OLD MANAGED");
    }
}
