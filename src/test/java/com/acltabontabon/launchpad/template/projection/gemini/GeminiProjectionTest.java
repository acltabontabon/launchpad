package com.acltabontabon.launchpad.template.projection.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.template.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiProjectionTest {

    private static final Rule SAMPLE_RULE = new Rule(
        "clean-code", "Clean Code", "must",
        "Write self-documenting code.", "Code is read more often than written.",
        null, null, null);

    private static final Skill SAMPLE_SKILL = new Skill(
        "add-feature", "Add a Feature", "When adding a new user-facing capability.",
        List.of("Identify the entry point."), List.of("A single PR."), null, null);

    private static final Checklist SAMPLE_CHECKLIST = new Checklist(
        "pr-checklist", "Pull Request Checklist",
        List.of(new ChecklistItem("scope", "Diff matches stated scope.", true)),
        null);

    private final GeminiProjection projection = new GeminiProjection();

    @Test
    void idAndDisplayName() {
        assertThat(projection.id()).isEqualTo("gemini");
        assertThat(projection.displayName()).isEqualTo("Gemini CLI");
        assertThat(projection.description()).contains("AGENTS.md");
    }

    @Test
    void emitsOnlyAGeminiSettingsFileNeverAGeminiMd() {
        var files = projection.project(null,
            List.of(SAMPLE_RULE), List.of(SAMPLE_SKILL), List.of(SAMPLE_CHECKLIST));

        assertThat(files).hasSize(1);
        var f = files.get(0);
        assertThat(f.relativePath()).isEqualTo(".gemini/settings.json");
        assertThat(f.kind()).isEqualTo(GeneratedFile.FileKind.CONFIG);
        assertThat(files).extracting(GeneratedFile::relativePath).doesNotContain("GEMINI.md");
    }

    @Test
    void pointsGeminiContextFileAtCanonicalAgentsMd() {
        var content = projection.project(null, List.of(), List.of(), List.of()).get(0).content();

        assertThat(content).contains("\"context\"");
        assertThat(content).contains("\"fileName\": \"AGENTS.md\"");
    }

    @Test
    void contentIsDeterministicAndIndependentOfTheModel() {
        var a = projection.project(null, List.of(SAMPLE_RULE), List.of(), List.of()).get(0).content();
        var b = projection.project(null, List.of(), List.of(SAMPLE_SKILL), List.of(SAMPLE_CHECKLIST))
            .get(0).content();

        assertThat(a).isEqualTo(b);
    }
}
