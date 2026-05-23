package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Adapter;
import com.acltabontabon.launchpad.standards.AdapterOutput;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Prompt;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContextTemplateEngineTest {

    private static final String RULE_BODY_MARKER = "Functions do one thing and stay at one level of abstraction.";
    private static final String RULE_TITLE = "Clean Code";
    private static final String PROJECT_NOTES_BODY =
        "## Skill: add-rest-endpoint\nWhen adding a new feature, you need to create a REST endpoint.";

    private static final Rule SAMPLE_RULE = new Rule(
        "clean-code", RULE_TITLE, "must",
        "Write self-documenting code. " + RULE_BODY_MARKER,
        "Code is read more often than written.",
        null, null, null);

    private static final Skill SAMPLE_SKILL = new Skill(
        "add-feature", "Add a Feature", "When adding a new user-facing capability.",
        List.of("Identify the entry point.", "Wire through existing layers."),
        List.of("A single PR with clear scope."), "Keep new types package-private.", null);

    private static final Checklist SAMPLE_CHECKLIST = new Checklist(
        "pr-checklist", "Pull Request Checklist",
        List.of(new ChecklistItem("scope", "Diff matches stated scope.", true)),
        null);

    private static final Prompt SAMPLE_PROMPT = new Prompt(
        "plan", "Implementation Plan Request", "You are preparing an implementation plan.");

    @Test
    void claudePrimaryFilePointsAtCompanionsInsteadOfInliningStandards() {
        var engine = engineWith(List.of(SAMPLE_RULE), List.of(SAMPLE_SKILL),
            List.of(SAMPLE_CHECKLIST), List.of(SAMPLE_PROMPT),
            claudeAdapterIncluding("rules", "skills", "checklists", "prompts"));

        var files = engine.buildFiles(sampleContext(), ContextTarget.CLAUDE,
            "Summary text.", PROJECT_NOTES_BODY);

        var primary = contentAt(files, "CLAUDE.md");
        assertThat(primary).contains("## Standards");
        assertThat(primary).contains(".ai/engineering-rules.md");
        assertThat(primary).contains(".ai/checklists.md");
        assertThat(primary).contains(".ai/prompts.md");
        assertThat(primary).contains(".ai/project-notes.md");
        assertThat(primary).contains("/add-feature");

        // No standards body or project-notes body inlined in the primary file.
        assertThat(primary).doesNotContain(RULE_BODY_MARKER);
        assertThat(primary).doesNotContain("### " + RULE_TITLE);
        assertThat(primary).doesNotContain("## Project-Specific Notes");
        assertThat(primary).doesNotContain("Skill: add-rest-endpoint");

        // Companion still carries the canonical content.
        var rulesMd = contentAt(files, ".ai/engineering-rules.md");
        assertThat(rulesMd).contains(RULE_BODY_MARKER);
        var notesMd = contentAt(files, ".ai/project-notes.md");
        assertThat(notesMd).contains("Skill: add-rest-endpoint");

        // Per-skill file still emitted so /<skill-id> invocation works.
        assertThat(pathsOf(files)).contains(".claude/skills/add-feature/SKILL.md");
    }

    @Test
    void cursorPrimaryFilePointsAtMdcCompanionsInsteadOfInliningStandards() {
        var engine = engineWith(List.of(SAMPLE_RULE), List.of(SAMPLE_SKILL),
            List.of(SAMPLE_CHECKLIST), List.of(SAMPLE_PROMPT),
            cursorAdapterIncluding("rules", "skills", "checklists", "prompts"));

        var files = engine.buildFiles(sampleContext(), ContextTarget.CURSOR,
            "Summary text.", PROJECT_NOTES_BODY);

        var primary = contentAt(files, ".cursor/rules/main.mdc");
        assertThat(primary).contains("## Standards");
        assertThat(primary).contains(".cursor/rules/engineering.mdc");
        assertThat(primary).contains(".cursor/rules/skills.mdc");
        assertThat(primary).contains(".cursor/rules/checklists.mdc");
        assertThat(primary).contains(".cursor/rules/prompts.mdc");
        assertThat(primary).contains(".cursor/rules/project-notes.mdc");

        assertThat(primary).doesNotContain(RULE_BODY_MARKER);
        assertThat(primary).doesNotContain("### " + RULE_TITLE);
        assertThat(primary).doesNotContain("Skill: add-rest-endpoint");

        var rulesMdc = contentAt(files, ".cursor/rules/engineering.mdc");
        assertThat(rulesMdc).contains(RULE_BODY_MARKER);
        var notesMdc = contentAt(files, ".cursor/rules/project-notes.mdc");
        assertThat(notesMdc).contains("Skill: add-rest-endpoint");
    }

    private static ContextTemplateEngine engineWith(
        List<Rule> rules, List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        Adapter adapter
    ) {
        var loader = mock(StandardsLoader.class);
        when(loader.loadRules(any())).thenReturn(rules);
        when(loader.loadSkills(any())).thenReturn(skills);
        when(loader.loadChecklists(any())).thenReturn(checklists);
        when(loader.loadPrompts(any())).thenReturn(prompts);
        when(loader.loadAdapter(any(), any())).thenReturn(Optional.of(adapter));

        var registry = mock(ProjectRegistry.class);
        when(registry.all()).thenReturn(List.of());

        return new ContextTemplateEngine(loader, registry);
    }

    private static Adapter claudeAdapterIncluding(String... includes) {
        return new Adapter("claude", "claude", "Claude Code adapter",
            List.of(new AdapterOutput("CLAUDE.md", "markdown", List.of(includes), Map.of())));
    }

    private static Adapter cursorAdapterIncluding(String... includes) {
        return new Adapter("cursor", "cursor", "Cursor adapter",
            List.of(new AdapterOutput(".cursor/rules/main.mdc", "mdc", List.of(includes),
                Map.of("description", "Primary project rules", "globs", "**/*"))));
    }

    private static ProjectContext sampleContext() {
        return new ProjectContext(
            "sample-project",
            Path.of(System.getProperty("java.io.tmpdir"), "launchpad-test-sample").toString(),
            new StackProfile("Java", "Maven", "Spring Boot", List.of()),
            List.of("src/main/java/Foo.java"),
            List.of(),
            Map.of(),
            List.of(),
            Map.of(),
            List.of(),
            null);
    }

    private static String contentAt(List<GeneratedFile> files, String path) {
        return files.stream()
            .filter(f -> f.relativePath().equals(path))
            .findFirst()
            .map(GeneratedFile::content)
            .orElseThrow(() -> new AssertionError("missing file: " + path
                + " (got: " + pathsOf(files) + ")"));
    }

    private static List<String> pathsOf(List<GeneratedFile> files) {
        return files.stream().map(GeneratedFile::relativePath).toList();
    }
}
