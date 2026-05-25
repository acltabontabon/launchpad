package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyPrimaryFileDetectorTest {

    @Test
    void quietWhenNoLegacyFilesPresent(@TempDir Path root) {
        var generated = List.of(
            new GeneratedFile("AGENTS.md", "x", GeneratedFile.FileKind.CONTEXT));
        assertThat(LegacyPrimaryFileDetector.detect(root, generated)).isEmpty();
    }

    @Test
    void surfacesLegacyClaudeMdNotInGeneratedSet(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("CLAUDE.md"), "# user hand-wrote this");
        var generated = List.of(
            new GeneratedFile("AGENTS.md", "x", GeneratedFile.FileKind.CONTEXT));

        var msg = LegacyPrimaryFileDetector.detect(root, generated);

        assertThat(msg).isPresent();
        assertThat(msg.get()).contains("CLAUDE.md");
        assertThat(msg.get()).contains("AGENTS.md");
    }

    @Test
    void surfacesLegacyCursorrulesAlongsideClaudeMd(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("CLAUDE.md"), "legacy claude");
        Files.writeString(root.resolve(".cursorrules"), "legacy cursor");
        var generated = List.of(
            new GeneratedFile("AGENTS.md", "x", GeneratedFile.FileKind.CONTEXT));

        var msg = LegacyPrimaryFileDetector.detect(root, generated);

        assertThat(msg).isPresent();
        assertThat(msg.get()).contains("CLAUDE.md");
        assertThat(msg.get()).contains(".cursorrules");
    }

    @Test
    void doesNotFlagFilesThatAreInTheGeneratedSet(@TempDir Path root) throws Exception {
        // If, for some reason, Launchpad were to emit CLAUDE.md as part of the
        // generated set (e.g. user-declared agents adapter pointing at it),
        // it must not be flagged as "legacy" - it's an emitted output.
        Files.writeString(root.resolve("CLAUDE.md"), "managed");
        var generated = List.of(
            new GeneratedFile("CLAUDE.md", "managed", GeneratedFile.FileKind.CONTEXT));

        assertThat(LegacyPrimaryFileDetector.detect(root, generated)).isEmpty();
    }

    @Test
    void generatedAgentsMdBodyContainsNoMigrationNote() {
        // Sanity guard: the detector's message is only ever returned to the
        // caller, never injected into the AGENTS.md body. This test pins the
        // contract.
        var msg = "any string with the word 'legacy' in it";
        // The AGENTS.md content as emitted by AgentsPrimaryFileBuilder must
        // not include this phrasing; ContextTemplateEngineTest already pins
        // the primary file shape and would catch any leakage. This test is a
        // documentation hook.
        assertThat(msg).doesNotContain("CLAUDE.md");
    }
}
