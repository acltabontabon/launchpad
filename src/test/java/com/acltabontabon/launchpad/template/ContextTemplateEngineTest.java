package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.scanner.PackageSummary;
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
    void claudePrimaryFileIsAssembledFromDeterministicSkeleton() {
        var engine = engineWith(List.of(SAMPLE_RULE), List.of(SAMPLE_SKILL),
            List.of(SAMPLE_CHECKLIST), List.of(SAMPLE_PROMPT),
            claudeAdapterIncluding("rules", "skills", "checklists", "prompts"));

        // The Claude path assembles the file from scanner facts and synthesis
        // fragments; only the per-target project-notes body is threaded through.
        var files = engine.buildFiles(sampleContext(), ContextTarget.CLAUDE,
            PROJECT_NOTES_BODY);

        var primary = contentAt(files, "CLAUDE.md");

        // Round-4 shape: title + tagline.
        assertThat(primary).startsWith("<!-- launchpad:managed:start -->\n# CLAUDE.md");
        assertThat(primary).doesNotContain("Launchpad prepares. Paid agents execute.");

        // Every required heading is emitted by the template engine, in order.
        // Round 9 removed `## How to work in this repo` (it just paraphrased
        // the deterministic `## Commands` block below it).
        int whatIdx = primary.indexOf("## What this project is");
        int commandsIdx = primary.indexOf("## Commands");
        int mapIdx = primary.indexOf("## Project map");
        int genIdx = primary.indexOf("## Generated context");
        int boundsIdx = primary.indexOf("## Boundaries for AI agents");
        assertThat(whatIdx).isPositive();
        assertThat(commandsIdx).isGreaterThan(whatIdx);
        assertThat(mapIdx).isGreaterThan(commandsIdx);
        assertThat(genIdx).isGreaterThan(mapIdx);
        assertThat(boundsIdx).isGreaterThan(genIdx);
        assertThat(primary).doesNotContain("## How to work in this repo");

        // Commands block content comes from the deterministic renderer.
        assertThat(primary).contains("./mvnw spring-boot:run");

        // The previous mega-prompt sections never appear.
        assertThat(primary).doesNotContain("## Project Overview");
        assertThat(primary).doesNotContain("## Stack\n");
        assertThat(primary).doesNotContain("## Workspace");
        assertThat(primary).doesNotContain("## Standards\n");
        assertThat(primary).doesNotContain("Cross-project MCP tools");

        // No standards body or project-notes body inlined in the primary file.
        assertThat(primary).doesNotContain(RULE_BODY_MARKER);
        assertThat(primary).doesNotContain("### " + RULE_TITLE);
        assertThat(primary).doesNotContain("## Project-Specific Notes");
        assertThat(primary).doesNotContain("Skill: add-rest-endpoint");

        // Companion files still carry the canonical content.
        var rulesMd = contentAt(files, ".ai/engineering-rules.md");
        assertThat(rulesMd).contains(RULE_BODY_MARKER);
        var notesMd = contentAt(files, ".ai/project-notes.md");
        assertThat(notesMd).contains("Skill: add-rest-endpoint");

        // Per-skill file still emitted so /<skill-id> invocation works.
        assertThat(pathsOf(files)).contains(".claude/skills/add-feature/SKILL.md");
    }

    @Test
    void claudePrimaryUsesReadmeIntroWhenPresent() {
        var engine = engineWith(List.of(SAMPLE_RULE), List.of(SAMPLE_SKILL),
            List.of(SAMPLE_CHECKLIST), List.of(SAMPLE_PROMPT),
            claudeAdapterIncluding("rules", "skills", "checklists", "prompts"));

        var ctx = contextWithReadmeIntro(
            "A reproducible benchmark of Spring Boot 4 + Oracle GraalVM Native Image 25.");
        var files = engine.buildFiles(ctx, ContextTarget.CLAUDE, PROJECT_NOTES_BODY);
        var primary = contentAt(files, "CLAUDE.md");

        assertThat(primary).contains(
            "A reproducible benchmark of Spring Boot 4 + Oracle GraalVM Native Image 25.");
    }

    @Test
    void claudePrimaryOmitsGeneratedContextOnNakedProject() {
        // No rules / no skills / no checklists / no prompts / no project notes
        // => no companion files beyond `.ai/index.md` and `.ai/stack.md`.
        // Those two ARE useful, so the section still renders pointing only at
        // them. Run this assertion to lock in the "honest pointers" contract.
        var engine = engineWith(List.of(), List.of(), List.of(), List.of(),
            claudeAdapterIncluding("rules", "skills"));

        var files = engine.buildFiles(sampleContext(), ContextTarget.CLAUDE, "");
        var primary = contentAt(files, "CLAUDE.md");

        // Section renders, but pointers are limited to what exists.
        assertThat(primary).contains("## Generated context");
        assertThat(primary).contains(".ai/index.md");
        assertThat(primary).contains(".ai/stack.md");
        // None of these companion files were emitted, so they must not appear
        // as pointers in the primary file.
        assertThat(primary).doesNotContain(".ai/engineering-rules.md");
        assertThat(primary).doesNotContain(".ai/checklists.md");
        assertThat(primary).doesNotContain(".ai/prompts.md");
        assertThat(primary).doesNotContain(".ai/project-notes.md");
        assertThat(primary).doesNotContain(".claude/skills/");
    }

    @Test
    void claudePrimaryOmitsProjectMapWhenNoPackages() {
        var engine = engineWith(List.of(), List.of(), List.of(), List.of(),
            claudeAdapterIncluding("rules", "skills"));

        var ctx = contextWithoutPackages();
        var files = engine.buildFiles(ctx, ContextTarget.CLAUDE, "");
        var primary = contentAt(files, "CLAUDE.md");

        assertThat(primary).doesNotContain("## Project map");
    }

    @Test
    void claudePrimaryOmitsCommandsAndWorkflowWhenBuildToolUnknown() {
        var engine = engineWith(List.of(), List.of(), List.of(), List.of(),
            claudeAdapterIncluding("rules", "skills"));

        var ctx = contextWithUnknownBuildTool();
        var files = engine.buildFiles(ctx, ContextTarget.CLAUDE, "");
        var primary = contentAt(files, "CLAUDE.md");

        assertThat(primary).doesNotContain("## Commands");
        assertThat(primary).doesNotContain("## How to work in this repo");
        // The naked file still survives - title + tagline + intro + boundaries.
        assertThat(primary).contains("# CLAUDE.md");
        assertThat(primary).contains("## Boundaries for AI agents");
    }

    @Test
    void claudePrimaryFallsBackToDeterministicIntroWhenNoReadmeOrPom() {
        var engine = engineWith(List.of(SAMPLE_RULE), List.of(SAMPLE_SKILL),
            List.of(SAMPLE_CHECKLIST), List.of(SAMPLE_PROMPT),
            claudeAdapterIncluding("rules", "skills", "checklists", "prompts"));

        var files = engine.buildFiles(sampleContext(), ContextTarget.CLAUDE, PROJECT_NOTES_BODY);
        var primary = contentAt(files, "CLAUDE.md");

        // Without README intro, pom <description>, or a synthesizer, the
        // deterministic fallback names the project + stack.
        assertThat(primary).contains("`sample-project`");
        assertThat(primary).contains("Spring Boot");
    }

    @Test
    void cursorPrimaryFilePointsAtMdcCompanionsInsteadOfInliningStandards() {
        var engine = engineWith(List.of(SAMPLE_RULE), List.of(SAMPLE_SKILL),
            List.of(SAMPLE_CHECKLIST), List.of(SAMPLE_PROMPT),
            cursorAdapterIncluding("rules", "skills", "checklists", "prompts"));

        var files = engine.buildFiles(sampleContext(), ContextTarget.CURSOR,
            PROJECT_NOTES_BODY);

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

    @Test
    void cursorPrimaryFileUsesDeterministicSkeletonNotMegaPromptSummary() {
        var engine = engineWith(List.of(SAMPLE_RULE), List.of(SAMPLE_SKILL),
            List.of(SAMPLE_CHECKLIST), List.of(SAMPLE_PROMPT),
            cursorAdapterIncluding("rules", "skills", "checklists", "prompts"));

        var files = engine.buildFiles(sampleContext(), ContextTarget.CURSOR,
            PROJECT_NOTES_BODY);

        var primary = contentAt(files, ".cursor/rules/main.mdc");

        // Cursor primary now mirrors the Claude path: deterministic skeleton
        // with section headings owned by Java. The legacy mega-prompt
        // sections must not appear.
        assertThat(primary).contains("description: Primary project rules");
        assertThat(primary).contains("globs: **/*");
        assertThat(primary).contains("# sample-project");
        int whatIdx = primary.indexOf("## What this project is");
        int commandsIdx = primary.indexOf("## Commands");
        int boundsIdx = primary.indexOf("## Boundaries for AI agents");
        assertThat(whatIdx).isPositive();
        assertThat(commandsIdx).isGreaterThan(whatIdx);
        assertThat(boundsIdx).isGreaterThan(commandsIdx);
        assertThat(primary).contains("./mvnw spring-boot:run");

        // Legacy mega-prompt sections must be absent.
        assertThat(primary).doesNotContain("## Project Context");
        assertThat(primary).doesNotContain("Cursor Rules - sample-project");
    }

    @Test
    void cursorPrimaryFileFallsBackToCursorrulesWithoutAdapter() {
        var loader = mock(StandardsLoader.class);
        when(loader.loadRules(any())).thenReturn(List.of(SAMPLE_RULE));
        when(loader.loadSkills(any())).thenReturn(List.of(SAMPLE_SKILL));
        when(loader.loadChecklists(any())).thenReturn(List.of());
        when(loader.loadPrompts(any())).thenReturn(List.of());
        when(loader.loadAdapter(any(), any())).thenReturn(Optional.empty());
        var registry = mock(ProjectRegistry.class);
        when(registry.all()).thenReturn(List.of());
        var engine = new ContextTemplateEngine(loader, registry, null);

        var files = engine.buildFiles(sampleContext(), ContextTarget.CURSOR, "");

        // No adapter -> primary lands at the legacy `.cursorrules` path, but
        // with the new deterministic shape (no frontmatter, same skeleton).
        var primary = contentAt(files, ".cursorrules");
        assertThat(primary).doesNotStartWith("---\n");
        assertThat(primary).contains("# sample-project");
        assertThat(primary).contains("## What this project is");
        assertThat(primary).contains("## Boundaries for AI agents");
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

        return new ContextTemplateEngine(loader, registry, null);
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
            // Round-5 ## Project map is only emitted when packageSummaries
            // is non-empty. Provide a representative entry so the happy-path
            // assertions still exercise the section ordering.
            List.of(new PackageSummary("src/main/java", 1, List.of("Foo"))),
            null);
    }

    private static ProjectContext contextWithoutPackages() {
        var base = sampleContext();
        return new ProjectContext(
            base.name(), base.rootPath(), base.stack(),
            base.sourceFiles(), base.testClassNames(),
            base.entryPoints(), base.dependencies(),
            base.fileSnippets(), List.of(),
            base.existingContextSummary(), base.documentation(),
            base.endpoints(), base.readmeIntro(), base.pomDescription(), base.mavenProfiles());
    }

    private static ProjectContext contextWithUnknownBuildTool() {
        var base = sampleContext();
        return new ProjectContext(
            base.name(), base.rootPath(),
            new StackProfile("Unknown", null, null, List.of()),
            base.sourceFiles(), base.testClassNames(),
            base.entryPoints(), base.dependencies(),
            base.fileSnippets(), base.packageSummaries(),
            base.existingContextSummary(), base.documentation(),
            base.endpoints(), base.readmeIntro(), base.pomDescription(), base.mavenProfiles());
    }

    private static ProjectContext contextWithReadmeIntro(String intro) {
        var base = sampleContext();
        return new ProjectContext(
            base.name(), base.rootPath(), base.stack(),
            base.sourceFiles(), base.testClassNames(),
            base.entryPoints(), base.dependencies(),
            base.fileSnippets(), base.packageSummaries(),
            base.existingContextSummary(), base.documentation(),
            base.endpoints(), intro, base.pomDescription(), base.mavenProfiles());
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
