package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.ai.ContextGeneratorService;
import com.acltabontabon.launchpad.ai.SynthesisJob;
import com.acltabontabon.launchpad.ai.SynthesisValidator;
import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.config.RegisteredProject;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import com.acltabontabon.launchpad.springboot.maven.MavenProfile;
import com.acltabontabon.launchpad.scanner.PackageSummary;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Adapter;
import com.acltabontabon.launchpad.standards.AdapterOutput;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Prompt;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.standards.StandardsLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Assembles final file content from AI-generated summaries and standards loaded from YAML.
 * <p>
 * Rendering modes:
 *   - Adapter-driven: when the standards source includes an adapter for the current target
 *     (claude / cursor), the adapter's first output drives the primary combined context file's
 *     path, frontmatter, and section list. Secondary files (.ai/engineering-rules.md,
 *     per-skill SKILL.md, etc.) are always emitted at hardcoded paths for tool-specific
 *     discoverability.
 *   - Legacy: when no adapter resolves (flat-format override or unconfigured remote), the
 *     primary file falls back to the previous hardcoded path (CLAUDE.md / .cursorrules) and
 *     content shape.
 */
@Component
public class ContextTemplateEngine {

    private final StandardsLoader standardsLoader;
    private final ProjectRegistry projectRegistry;
    @Nullable
    private final ContextGeneratorService generator;
    private final SynthesisPromptLoader synthesisPrompts = new SynthesisPromptLoader();

    public ContextTemplateEngine(StandardsLoader standardsLoader,
                                 ProjectRegistry projectRegistry,
                                 @Nullable ContextGeneratorService generator) {
        this.standardsLoader = standardsLoader;
        this.projectRegistry = projectRegistry;
        this.generator = generator;
    }

    public List<GeneratedFile> buildFiles(
        ProjectContext ctx,
        ContextTarget target,
        String targetSpecificContent
    ) {
        var projectRoot = Path.of(ctx.rootPath());
        var rules = standardsLoader.loadRules(projectRoot);
        var skills = standardsLoader.loadSkills(projectRoot);
        var checklists = standardsLoader.loadChecklists(projectRoot);
        var prompts = standardsLoader.loadPrompts(projectRoot);
        var adapter = standardsLoader.loadAdapter(projectRoot, adapterIdFor(target));

        return switch (target) {
            case CLAUDE -> buildClaudeFiles(ctx, targetSpecificContent,
                rules, skills, checklists, prompts, adapter);
            case CURSOR -> buildCursorFiles(ctx, targetSpecificContent,
                rules, skills, checklists, prompts, adapter);
        };
    }

    private static String adapterIdFor(ContextTarget target) {
        return switch (target) {
            case CLAUDE -> "claude";
            case CURSOR -> "cursor";
        };
    }

    // === Claude ===

    private List<GeneratedFile> buildClaudeFiles(
        ProjectContext ctx, String llmSkills,
        List<Rule> rules, List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        Optional<Adapter> adapter
    ) {
        var files = new ArrayList<GeneratedFile>();

        // Decide which companion files we'll emit and hand the list to the
        // primary assembler. That keeps the primary file's `## Generated
        // context` pointers honest - we never point at a `.ai/checklists.md`
        // we didn't write.
        var companions = collectClaudeCompanions(ctx, rules, skills, checklists, prompts, llmSkills);
        var companionPaths = new java.util.LinkedHashSet<String>();
        for (var c : companions) companionPaths.add(c.relativePath());

        var primaryPath = adapter.flatMap(ContextTemplateEngine::firstOutput)
            .map(AdapterOutput::path).orElse("CLAUDE.md");
        files.add(new GeneratedFile(primaryPath,
            MergeMarkers.wrap(buildClaudePrimary(ctx, companionPaths)),
            GeneratedFile.FileKind.CONTEXT));
        files.addAll(companions);
        return files;
    }

    /**
     * Builds the ordered list of `.ai/*` and `.claude/skills/*` companion
     * files for the Claude target. Conditional entries (checklists, prompts,
     * project notes, skills) appear only when their source data is non-empty
     * so the primary file's `## Generated context` block can be gated on the
     * real output set.
     */
    private List<GeneratedFile> collectClaudeCompanions(
        ProjectContext ctx,
        List<Rule> rules, List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        String llmSkills
    ) {
        var out = new ArrayList<GeneratedFile>();
        out.add(new GeneratedFile(".ai/index.md",
            buildAiIndex(ctx, skills, !checklists.isEmpty(), !prompts.isEmpty(),
                llmSkills != null && !llmSkills.isBlank()),
            GeneratedFile.FileKind.INDEX));
        if (!rules.isEmpty()) {
            out.add(new GeneratedFile(".ai/engineering-rules.md", buildEngineeringRulesMd(rules),
                GeneratedFile.FileKind.RULES));
        }
        out.add(new GeneratedFile(".ai/stack.md", buildStackMd(ctx),
            GeneratedFile.FileKind.CONTEXT));
        if (!checklists.isEmpty()) {
            out.add(new GeneratedFile(".ai/checklists.md", buildChecklistsMd(checklists),
                GeneratedFile.FileKind.RULES));
        }
        if (!prompts.isEmpty()) {
            out.add(new GeneratedFile(".ai/prompts.md", buildPromptsMd(prompts),
                GeneratedFile.FileKind.RULES));
        }
        if (llmSkills != null && !llmSkills.isBlank()) {
            out.add(new GeneratedFile(".ai/project-notes.md", buildProjectNotesMd(ctx, llmSkills),
                GeneratedFile.FileKind.CONTEXT));
        }
        skills.forEach(s -> out.add(new GeneratedFile(
            ".claude/skills/" + s.id() + "/SKILL.md",
            buildClaudeSkillFile(s),
            GeneratedFile.FileKind.SKILL
        )));
        return out;
    }

    // === Cursor ===

    private List<GeneratedFile> buildCursorFiles(
        ProjectContext ctx, String llmRules,
        List<Rule> rules, List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        Optional<Adapter> adapter
    ) {
        var files = new ArrayList<GeneratedFile>();

        // Collect companions first so the primary's Standards pointer block can
        // limit itself to files we actually emit (same shape as Claude).
        var companions = collectCursorCompanions(ctx, rules, skills, checklists, prompts, llmRules);
        var companionPaths = new java.util.LinkedHashSet<String>();
        for (var c : companions) companionPaths.add(c.relativePath());

        var primaryOutput = adapter.flatMap(ContextTemplateEngine::firstOutput);
        var primaryPath = primaryOutput.map(AdapterOutput::path).orElse(".cursorrules");
        files.add(new GeneratedFile(primaryPath,
            MergeMarkers.wrap(buildCursorPrimary(ctx, primaryOutput, companionPaths,
                skills, checklists, prompts, llmRules)),
            GeneratedFile.FileKind.CONTEXT));
        files.addAll(companions);
        return files;
    }

    /**
     * Cursor's `.cursor/rules/*.mdc` siblings. Conditional entries appear only
     * when their source data is non-empty so the primary file's `## Standards`
     * pointer block can be gated on the real output set, mirroring the Claude
     * companion collector.
     */
    private List<GeneratedFile> collectCursorCompanions(
        ProjectContext ctx,
        List<Rule> rules, List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        String llmRules
    ) {
        var out = new ArrayList<GeneratedFile>();
        out.add(new GeneratedFile(".cursor/rules/engineering.mdc", buildCursorEngineeringRules(rules),
            GeneratedFile.FileKind.RULES));
        out.add(new GeneratedFile(".cursor/rules/skills.mdc", buildCursorSkills(skills),
            GeneratedFile.FileKind.RULES));
        out.add(new GeneratedFile(".cursor/rules/stack.mdc", buildCursorStackRules(ctx),
            GeneratedFile.FileKind.CONTEXT));
        if (!checklists.isEmpty()) {
            out.add(new GeneratedFile(".cursor/rules/checklists.mdc", buildCursorChecklists(checklists),
                GeneratedFile.FileKind.RULES));
        }
        if (!prompts.isEmpty()) {
            out.add(new GeneratedFile(".cursor/rules/prompts.mdc", buildCursorPrompts(prompts),
                GeneratedFile.FileKind.RULES));
        }
        if (llmRules != null && !llmRules.isBlank()) {
            out.add(new GeneratedFile(".cursor/rules/project-notes.mdc",
                buildCursorProjectNotes(ctx, llmRules), GeneratedFile.FileKind.CONTEXT));
        }
        return out;
    }

    private static Optional<AdapterOutput> firstOutput(Adapter a) {
        return a.outputs() == null || a.outputs().isEmpty()
            ? Optional.empty()
            : Optional.of(a.outputs().get(0));
    }

    // === Claude primary file (round-4 deterministic skeleton + chunked synthesis) ===

    /**
     * Deterministic CLAUDE.md skeleton. Java owns every heading, table, and
     * managed marker; the local model only fills bounded body fragments
     * (intro paragraph, workflow paragraph, project-map bullets, API
     * bullets, build-profile bullets), with deterministic fallbacks for
     * every job. The model can never invent the document structure.
     */
    private String buildClaudePrimary(ProjectContext ctx, java.util.Set<String> companionPaths) {
        var sb = new StringBuilder();
        sb.append("# CLAUDE.md\n\n");

        sb.append("## What this project is\n\n").append(introBody(ctx)).append("\n\n");

        // CommandsRenderer already emits its own "## Commands" heading;
        // skip entirely when no build tool was detected.
        if (CommandsRenderer.hasCommands(ctx.stack())) {
            sb.append(CommandsRenderer.render(ctx.stack()));
        }

        var classFacts = com.acltabontabon.launchpad.scanner.ProjectClassFacts.collect(
            java.nio.file.Path.of(ctx.rootPath()), ctx.sourceFiles(), ctx.endpoints());
        if (!classFacts.isEmpty()) {
            sb.append("## Architecture\n\n");
            var narrative = architectureNarrative(ctx, classFacts);
            if (!narrative.isBlank()) sb.append(narrative).append("\n\n");
            sb.append(ArchitectureTreeRenderer.render(classFacts)).append("\n");
        } else if (!ctx.packageSummaries().isEmpty()) {
            // Fallback for projects whose JVM sources we couldn't classify
            // (no top-level declaration matched, or all files filtered out):
            // emit the simpler `## Project map` tree so the section is not lost.
            sb.append("## Project map\n\n");
            sb.append(FileTreeRenderer.render(ctx.packageSummaries())).append("\n");
        }

        var allEndpoints = combinedEndpoints(ctx);
        if (!allEndpoints.isEmpty()) {
            sb.append("## Endpoints\n\n");
            var notes = endpointNotes(ctx, allEndpoints);
            sb.append(EndpointsTableRenderer.render(allEndpoints, notes)).append("\n");
        }

        if (!ctx.mavenProfiles().isEmpty()) {
            sb.append("## Build profiles\n\n");
            sb.append(BuildProfilesRenderer.render(ctx.mavenProfiles())).append("\n");
            var profileBullets = buildProfilesBullets(ctx);
            if (!profileBullets.isEmpty()) sb.append(profileBullets).append("\n");
            sb.append("\n");
        }

        var generatedContextBlock = renderGeneratedContextBlock(companionPaths);
        if (!generatedContextBlock.isEmpty()) sb.append(generatedContextBlock);

        sb.append("## Boundaries for AI agents\n\n");
        sb.append("- Do not rewrite generated context unless asked.\n");
        sb.append("- Prefer existing commands from this file.\n");
        sb.append("- Treat scanner output as evidence, not absolute truth.\n");
        sb.append("- Keep changes scoped to the requested task.\n");

        return sb.toString();
    }

    // === Cursor primary file (round-4 deterministic skeleton + chunked synthesis) ===

    /**
     * Deterministic `.cursorrules` (or adapter-provided primary `.mdc`)
     * skeleton. Same section sequence as `buildClaudePrimary` so both targets
     * share one mental model; only the title preamble and Standards pointer
     * paths differ. Java owns every heading; synthesis fills bounded body
     * fragments with deterministic fallbacks per job.
     */
    private String buildCursorPrimary(
        ProjectContext ctx,
        Optional<AdapterOutput> primaryOutput,
        java.util.Set<String> companionPaths,
        List<Skill> skills, List<Checklist> checklists, List<Prompt> prompts,
        String llmRules
    ) {
        var sb = new StringBuilder();

        // Adapter-driven frontmatter (Cursor convention). When no adapter
        // resolves, omit the block entirely - the legacy `.cursorrules` had
        // no frontmatter either.
        primaryOutput.ifPresent(out -> {
            var fm = out.frontmatter();
            if (fm != null && !fm.isEmpty()) {
                sb.append("---\n");
                fm.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
                sb.append("---\n\n");
            }
        });

        sb.append("# ").append(ctx.name()).append("\n\n");

        sb.append("## What this project is\n\n").append(introBody(ctx)).append("\n\n");

        if (CommandsRenderer.hasCommands(ctx.stack())) {
            sb.append(CommandsRenderer.render(ctx.stack()));
        }

        var classFacts = com.acltabontabon.launchpad.scanner.ProjectClassFacts.collect(
            java.nio.file.Path.of(ctx.rootPath()), ctx.sourceFiles(), ctx.endpoints());
        if (!classFacts.isEmpty()) {
            sb.append("## Architecture\n\n");
            var narrative = architectureNarrative(ctx, classFacts);
            if (!narrative.isBlank()) sb.append(narrative).append("\n\n");
            sb.append(ArchitectureTreeRenderer.render(classFacts)).append("\n");
        } else if (!ctx.packageSummaries().isEmpty()) {
            sb.append("## Project map\n\n");
            sb.append(FileTreeRenderer.render(ctx.packageSummaries())).append("\n");
        }

        var allEndpoints = combinedEndpoints(ctx);
        if (!allEndpoints.isEmpty()) {
            sb.append("## Endpoints\n\n");
            var notes = endpointNotes(ctx, allEndpoints);
            sb.append(EndpointsTableRenderer.render(allEndpoints, notes)).append("\n");
        }

        if (!ctx.mavenProfiles().isEmpty()) {
            sb.append("## Build profiles\n\n");
            sb.append(BuildProfilesRenderer.render(ctx.mavenProfiles())).append("\n");
            var profileBullets = buildProfilesBullets(ctx);
            if (!profileBullets.isEmpty()) sb.append(profileBullets).append("\n");
            sb.append("\n");
        }

        var standardsBlock = renderCursorStandardsBlock(companionPaths);
        if (!standardsBlock.isEmpty()) sb.append(standardsBlock);

        sb.append("## Boundaries for AI agents\n\n");
        sb.append("- Do not rewrite generated context unless asked.\n");
        sb.append("- Prefer existing commands from this file.\n");
        sb.append("- Treat scanner output as evidence, not absolute truth.\n");
        sb.append("- Keep changes scoped to the requested task.\n");

        return sb.toString();
    }

    /**
     * Cursor analogue of {@link #renderGeneratedContextBlock(java.util.Set)}.
     * Renders a `## Standards` pointer list keyed on the `.cursor/rules/*.mdc`
     * companion files actually emitted, so we never point at a file we did not
     * write.
     */
    private static String renderCursorStandardsBlock(java.util.Set<String> companionPaths) {
        var entries = new ArrayList<String>();
        if (companionPaths.contains(".cursor/rules/engineering.mdc"))
            entries.add("- **Engineering rules:** see `.cursor/rules/engineering.mdc`");
        if (companionPaths.contains(".cursor/rules/skills.mdc"))
            entries.add("- **Workflow skills:** see `.cursor/rules/skills.mdc`");
        if (companionPaths.contains(".cursor/rules/stack.mdc"))
            entries.add("- **Stack and dependencies:** see `.cursor/rules/stack.mdc`");
        if (companionPaths.contains(".cursor/rules/checklists.mdc"))
            entries.add("- **Checklists:** see `.cursor/rules/checklists.mdc`");
        if (companionPaths.contains(".cursor/rules/prompts.mdc"))
            entries.add("- **Reusable prompts:** see `.cursor/rules/prompts.mdc`");
        if (companionPaths.contains(".cursor/rules/project-notes.mdc"))
            entries.add("- **Project-specific notes:** see `.cursor/rules/project-notes.mdc`");

        if (entries.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("## Standards\n\n");
        sb.append("Canonical sources for this project's engineering standards. The primary file ")
          .append("references them so a single edit propagates everywhere.\n\n");
        entries.forEach(e -> sb.append(e).append("\n"));
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Renders the `## Generated context` block as pointers to companion files
     * that were actually emitted. Returns an empty string when no companion
     * file qualifies, so the section is omitted entirely on naked projects
     * with no standards / no rules / no skills configured.
     */
    private static String renderGeneratedContextBlock(java.util.Set<String> companionPaths) {
        var entries = new ArrayList<String>();
        if (companionPaths.contains(".ai/index.md"))
            entries.add("- `.ai/index.md` - file map for this directory");
        if (companionPaths.contains(".ai/stack.md"))
            entries.add("- `.ai/stack.md` - stack and dependency notes");
        if (companionPaths.contains(".ai/engineering-rules.md"))
            entries.add("- `.ai/engineering-rules.md` - team coding rules");
        if (companionPaths.contains(".ai/checklists.md"))
            entries.add("- `.ai/checklists.md` - verification checklists");
        if (companionPaths.contains(".ai/prompts.md"))
            entries.add("- `.ai/prompts.md` - reusable prompts");
        if (companionPaths.contains(".ai/project-notes.md"))
            entries.add("- `.ai/project-notes.md` - project-specific notes");
        boolean hasSkillFile = companionPaths.stream().anyMatch(p -> p.startsWith(".claude/skills/"));
        if (hasSkillFile)
            entries.add("- `.claude/skills/<skill-id>/SKILL.md` - invoke via `/<skill-id>`");

        if (entries.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append("## Generated context\n\n");
        sb.append("Before making changes, read:\n\n");
        entries.forEach(e -> sb.append(e).append("\n"));
        sb.append("\n");
        return sb.toString();
    }

    // ── Synthesis bodies + deterministic fallbacks ─────────────────────────

    private String introBody(ProjectContext ctx) {
        // Round-6: always run synthesis when synthesis is enabled AND we
        // have at least one signal. The LLM rephrases + fuses the README
        // intro, pom <description>, and structural facts into a richer
        // paragraph. The fallback chain (rejection or disabled) is:
        // README intro -> pom desc -> deterministic one-liner.
        Supplier<String> fallback = () -> {
            if (!ctx.readmeIntro().isBlank()) return ctx.readmeIntro();
            if (!ctx.pomDescription().isBlank()) return ctx.pomDescription();
            return deterministicIntro(ctx);
        };

        boolean hasAnySignal = !ctx.readmeIntro().isBlank()
            || !ctx.pomDescription().isBlank()
            || !ctx.packageSummaries().isEmpty()
            || (ctx.stack().framework() != null && !ctx.stack().framework().isBlank());
        if (!hasAnySignal) return fallback.get();

        var entryPoint = ctx.entryPoints().isEmpty()
            ? "none"
            : ctx.entryPoints().values().iterator().next();
        var template = synthesisPrompts.load("project-intro")
            .replace("{name}", ctx.name())
            .replace("{language}", nonBlank(ctx.stack().language(), "unknown"))
            .replace("{framework}", nonBlank(ctx.stack().framework(), "none"))
            .replace("{buildTool}", nonBlank(ctx.stack().buildTool(), "unknown"))
            .replace("{readmeIntro}", nonBlank(ctx.readmeIntro(), "none"))
            .replace("{pomDescription}", nonBlank(ctx.pomDescription(), "none"))
            .replace("{entryPoint}", entryPoint)
            .replace("{topPackages}", topPackagePaths(ctx, 5));

        var job = new SynthesisJob(
            "project-intro", template, SynthesisValidator.Shape.PROSE,
            4000, 900,
            fallback,
            introAllowlist(ctx));
        return runSynthesis(job);
    }

    /**
     * Two-to-three sentence architectural-shape paragraph for the new
     * `## Architecture` section. Inputs are the tree-rendered class facts so
     * the model sees real package + class evidence; the allowlist constrains
     * any backticked reference in the output.
     */
    private String architectureNarrative(ProjectContext ctx,
                                         List<com.acltabontabon.launchpad.scanner.ClassFact> facts) {
        var tree = ArchitectureTreeRenderer.render(facts);
        var template = synthesisPrompts.load("architecture-narrative")
            .replace("{tree}", tree);
        var job = new SynthesisJob(
            "architecture-narrative", template, SynthesisValidator.Shape.PROSE,
            6000, 500,
            () -> "",
            architectureAllowlist(facts));
        return runSynthesis(job);
    }

    private static java.util.Set<String> architectureAllowlist(List<com.acltabontabon.launchpad.scanner.ClassFact> facts) {
        var out = new java.util.LinkedHashSet<String>();
        for (var f : facts) {
            out.add(f.leafPackage());
            out.add(f.name());
            out.addAll(f.impls());
            out.addAll(f.routes());
        }
        return out;
    }

    private String projectMapBullets(ProjectContext ctx) {
        // Round-6: lifted the `< 2 packages` skip. With representative source
        // snippets available, the model can describe a single-package layout
        // grounded in real class names. Allowlist still catches invented ones.
        if (ctx.packageSummaries().isEmpty()) return "";
        var template = synthesisPrompts.load("project-map")
            .replace("{packages}", renderPackagesForPrompt(ctx.packageSummaries()))
            .replace("{packageSources}", renderPackageRepresentativesForPrompt(ctx));
        var job = new SynthesisJob(
            "project-map", template, SynthesisValidator.Shape.BULLETS,
            10000, 800,
            () -> "",
            packageAllowlistWithSources(ctx));
        return runSynthesis(job);
    }

    /**
     * Combined endpoint list for the `## Endpoints` table: controller routes
     * detected by `EndpointExtractor` followed by Spring Actuator routes
     * detected by `ActuatorDetector`. Returns empty when neither is present.
     */
    private static List<Endpoint> combinedEndpoints(ProjectContext ctx) {
        if (ctx.endpoints().isEmpty() && ctx.actuatorEndpoints().isEmpty()) return List.of();
        var out = new ArrayList<Endpoint>(ctx.endpoints().size() + ctx.actuatorEndpoints().size());
        out.addAll(ctx.endpoints());
        out.addAll(ctx.actuatorEndpoints());
        return out;
    }

    /**
     * Per-endpoint Notes for the `## Endpoints` table. One batched LLM call
     * produces {@code "METHOD /path => note"} lines that the engine parses
     * into a map keyed by {@link EndpointsTableRenderer#key}. Actuator
     * endpoints get their deterministic note pre-filled so a thin or
     * rejected LLM response still produces useful Notes cells.
     */
    private Map<String, String> endpointNotes(ProjectContext ctx, List<Endpoint> all) {
        var notes = new java.util.LinkedHashMap<String, String>(ctx.actuatorNotes());
        var hasActuator = !ctx.actuatorEndpoints().isEmpty();
        var hasBuildInfo = ctx.fileSnippets().entrySet().stream()
            .anyMatch(e -> e.getKey().toLowerCase().endsWith("pom.xml")
                && e.getValue() != null
                && e.getValue().contains("<goal>build-info</goal>"));

        var template = synthesisPrompts.load("endpoint-notes")
            .replace("{endpoints}", renderEndpointsForPrompt(all))
            .replace("{controllerSources}", renderControllerSourcesForPrompt(ctx))
            .replace("{hasActuator}", String.valueOf(hasActuator))
            .replace("{hasBuildInfoGoal}", String.valueOf(hasBuildInfo))
            .replace("{actuatorHints}", renderActuatorHints(ctx.actuatorNotes()));

        var allowedKeys = new java.util.LinkedHashSet<String>();
        for (var ep : all) allowedKeys.add(EndpointsTableRenderer.key(ep));

        var job = new SynthesisJob(
            "endpoint-notes", template, SynthesisValidator.Shape.LINES,
            12000, 1200,
            () -> "",
            allowedKeys);
        var raw = runSynthesis(job);
        if (raw == null || raw.isBlank()) return notes;

        for (var raw_line : raw.split("\n")) {
            var line = raw_line.strip();
            if (line.isEmpty()) continue;
            int sep = line.indexOf("=>");
            if (sep < 0) continue;
            var key = line.substring(0, sep).strip();
            var value = line.substring(sep + 2).strip();
            if (!allowedKeys.contains(key)) continue;
            // LLM note wins over the deterministic fallback when non-empty.
            if (!value.isEmpty()) notes.put(key, value);
        }
        return notes;
    }

    private static String renderActuatorHints(Map<String, String> hints) {
        if (hints == null || hints.isEmpty()) return "(none - no actuator endpoints detected)";
        var sb = new StringBuilder();
        hints.forEach((key, note) ->
            sb.append("  ").append(key).append(" => ")
              .append(note.isBlank() ? "(empty - self-explanatory)" : note).append("\n"));
        return sb.toString().stripTrailing();
    }

    private String buildProfilesBullets(ProjectContext ctx) {
        if (ctx.mavenProfiles().isEmpty()) return "";
        var template = synthesisPrompts.load("build-profiles")
            .replace("{profiles}", renderProfilesForPrompt(ctx.mavenProfiles()))
            .replace("{profileXml}", renderProfileRawXmlForPrompt(ctx));
        var job = new SynthesisJob(
            "build-profiles", template, SynthesisValidator.Shape.BULLETS,
            10000, 700,
            () -> "",
            profileAllowlistWithXml(ctx));
        return runSynthesis(job);
    }

    // ── Allowlist builders ─────────────────────────────────────────────────

    private static java.util.Set<String> packageAllowlist(ProjectContext ctx) {
        var out = new java.util.LinkedHashSet<String>();
        for (var pkg : ctx.packageSummaries()) {
            out.add(pkg.path());
            if (pkg.path().contains("/")) {
                // Add the leaf segment so the model can reference `controller/`
                // when the path is `src/main/java/com/acme/controller`.
                var segs = pkg.path().split("/");
                out.add(segs[segs.length - 1]);
            }
            for (var sym : pkg.sampleSymbols()) out.add(sym);
        }
        return out;
    }

    private static java.util.Set<String> profileAllowlist(ProjectContext ctx) {
        var out = new java.util.LinkedHashSet<String>();
        for (var p : ctx.mavenProfiles()) {
            out.add(p.id());
            out.addAll(p.keyFlags());
        }
        return out;
    }

    // ── Round-6 evidence renderers ────────────────────────────────────────

    private static String renderControllerSourcesForPrompt(ProjectContext ctx) {
        var sources = ctx.controllerSources();
        if (sources.isEmpty()) return "(none)";
        var sb = new StringBuilder();
        for (var entry : sources.entrySet()) {
            sb.append("\n--- ").append(entry.getKey()).append(" ---\n");
            sb.append(entry.getValue()).append("\n");
        }
        return sb.toString().strip();
    }

    private static String renderProfileRawXmlForPrompt(ProjectContext ctx) {
        var raw = ctx.profileRawXml();
        if (raw.isEmpty()) return "(none)";
        var sb = new StringBuilder();
        for (var entry : raw.entrySet()) {
            sb.append("\n--- ").append(entry.getKey()).append(" ---\n");
            sb.append(entry.getValue()).append("\n");
        }
        return sb.toString().strip();
    }

    private static String renderPackageRepresentativesForPrompt(ProjectContext ctx) {
        var reps = ctx.packageRepresentatives();
        if (reps.isEmpty()) return "(none)";
        var sb = new StringBuilder();
        for (var entry : reps.entrySet()) {
            sb.append("\n--- ").append(entry.getKey()).append(" ---\n");
            sb.append(entry.getValue()).append("\n");
        }
        return sb.toString().strip();
    }


    // ── Round-6 extended allowlist builders ────────────────────────────────

    /**
     * Allowlist for the project-intro paragraph. Permissive on purpose - the
     * intro is prose, so we cover the project name, framework, language,
     * package leaves, sample symbols, and any words longer than four chars
     * lifted from the README intro / pom description.
     */
    private static java.util.Set<String> introAllowlist(ProjectContext ctx) {
        var out = new java.util.LinkedHashSet<String>();
        out.add(ctx.name());
        if (ctx.stack().framework() != null) out.add(ctx.stack().framework());
        if (ctx.stack().language() != null) out.add(ctx.stack().language());
        if (ctx.stack().buildTool() != null) out.add(ctx.stack().buildTool());
        out.addAll(packageAllowlist(ctx));
        addLongWords(out, ctx.readmeIntro());
        addLongWords(out, ctx.pomDescription());
        return out;
    }

    private static java.util.Set<String> packageAllowlistWithSources(ProjectContext ctx) {
        var out = packageAllowlist(ctx);
        ctx.packageRepresentatives().values().forEach(body -> addIdentifierTokens(out, body));
        return out;
    }

    private static java.util.Set<String> profileAllowlistWithXml(ProjectContext ctx) {
        var out = profileAllowlist(ctx);
        ctx.profileRawXml().values().forEach(body -> addIdentifierTokens(out, body));
        return out;
    }

    private static final java.util.regex.Pattern WORD_TOKEN = java.util.regex.Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_-]{3,}");

    private static void addLongWords(java.util.Set<String> sink, String text) {
        if (text == null || text.isEmpty()) return;
        var m = WORD_TOKEN.matcher(text);
        while (m.find()) sink.add(m.group());
    }

    private static final java.util.regex.Pattern IDENTIFIER_TOKEN = java.util.regex.Pattern.compile(
        "\\b[A-Za-z_][A-Za-z0-9_]{2,}\\b");

    private static void addIdentifierTokens(java.util.Set<String> sink, String text) {
        if (text == null || text.isEmpty()) return;
        var m = IDENTIFIER_TOKEN.matcher(text);
        while (m.find()) sink.add(m.group());
    }

    private String runSynthesis(SynthesisJob job) {
        // Without a generator (tests, AI disabled by Spring config) go straight to fallback.
        if (generator == null) return job.fallback().get();
        return generator.synthesize(job);
    }

    // ── Deterministic fallbacks ────────────────────────────────────────────

    private static String deterministicIntro(ProjectContext ctx) {
        var framework = ctx.stack().framework();
        var language = ctx.stack().language();
        var buildTool = ctx.stack().buildTool();
        boolean knownFramework = framework != null && !framework.isBlank();
        boolean knownLanguage = language != null && !language.isBlank() && !"Unknown".equalsIgnoreCase(language);
        boolean knownBuildTool = buildTool != null && !buildTool.isBlank();

        var sb = new StringBuilder();
        sb.append("`").append(ctx.name()).append("`");
        if (knownFramework || knownLanguage) {
            sb.append(" is a ");
            sb.append(knownFramework ? framework : language);
            sb.append(" project");
        } else {
            sb.append(" is a project with no detected stack");
        }
        if (knownBuildTool) sb.append(" built with ").append(buildTool);
        sb.append(".");
        if (!ctx.packageSummaries().isEmpty()) {
            sb.append(" Source is organised across ")
              .append(ctx.packageSummaries().size())
              .append(" top-level packages.");
        }
        return sb.toString().strip();
    }

    // Inputs to bullet-shape synthesis jobs intentionally do NOT use "- "
    // markers. Small models otherwise parrot the input bullets back wrapped
    // in backticks, producing `- `- /path` invokes ...` double-bullet noise.
    // Plain `name -> detail` lines give the model facts without a template
    // to copy.

    private static String renderPackagesForPrompt(List<PackageSummary> packages) {
        var sb = new StringBuilder();
        int cap = Math.min(packages.size(), 8);
        for (int i = 0; i < cap; i++) {
            var p = packages.get(i);
            sb.append(p.path()).append("/");
            if (!p.sampleSymbols().isEmpty()) {
                sb.append(" -> ").append(String.join(", ", p.sampleSymbols()));
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private static String renderEndpointsForPrompt(List<Endpoint> endpoints) {
        var sb = new StringBuilder();
        for (var ep : endpoints) {
            sb.append(ep.method()).append(" ").append(ep.path());
            if (!ep.handler().isBlank()) sb.append(" => ").append(ep.handler());
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private static String renderProfilesForPrompt(List<MavenProfile> profiles) {
        var sb = new StringBuilder();
        for (var p : profiles) {
            sb.append(p.id());
            if (!p.activation().isBlank()) sb.append(" (").append(p.activation()).append(")");
            if (!p.keyFlags().isEmpty()) sb.append(" ").append(String.join(" ", p.keyFlags()));
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private static String topPackagePaths(ProjectContext ctx, int n) {
        var packages = ctx.packageSummaries();
        if (packages == null || packages.isEmpty()) return "none";
        var sb = new StringBuilder();
        int cap = Math.min(packages.size(), n);
        for (int i = 0; i < cap; i++) {
            if (i > 0) sb.append(", ");
            sb.append("`").append(packages.get(i).path()).append("`");
        }
        return sb.toString();
    }

    private static String nonBlank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    /** Loads synthesis prompt templates from the classpath at first use. */
    private static final class SynthesisPromptLoader {
        private final java.util.concurrent.ConcurrentHashMap<String, String> cache = new java.util.concurrent.ConcurrentHashMap<>();

        String load(String id) {
            return cache.computeIfAbsent(id, this::read);
        }

        private String read(String id) {
            var resource = "prompts/synthesis/" + id + ".txt";
            try (InputStream in = SynthesisPromptLoader.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) throw new IllegalStateException("missing synthesis prompt: " + resource);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("failed to load " + resource, e);
            }
        }
    }

    // === Skill files (Claude) ===

    private String buildClaudeSkillFile(Skill skill) {
        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skill.id()).append("\n");
        if (skill.trigger() != null && !skill.trigger().isBlank()) {
            sb.append("description: ").append(skill.trigger()).append("\n");
        }
        sb.append("---\n\n");
        sb.append("# ").append(skillTitle(skill)).append("\n\n");
        if (skill.trigger() != null && !skill.trigger().isBlank()) {
            sb.append("**Trigger:** ").append(skill.trigger()).append("\n\n");
        }
        if (skill.steps() != null && !skill.steps().isEmpty()) {
            sb.append("## Steps\n\n");
            int i = 1;
            for (var step : skill.steps()) {
                sb.append(i++).append(". ").append(step).append("\n");
            }
            sb.append("\n");
        }
        if (skill.outputExpectations() != null && !skill.outputExpectations().isEmpty()) {
            sb.append("## Expected Output\n\n");
            skill.outputExpectations().forEach(o -> sb.append("- ").append(o).append("\n"));
            sb.append("\n");
        }
        if (skill.notes() != null && !skill.notes().isBlank()) {
            sb.append("## Notes\n\n").append(skill.notes()).append("\n");
        }
        return sb.toString();
    }

    private static String skillTitle(Skill skill) {
        if (skill.title() != null && !skill.title().isBlank()) return skill.title();
        return titleFromId(skill.id());
    }

    private static String titleFromId(String id) {
        var parts = id.split("-");
        var sb = new StringBuilder();
        for (var p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    // === Secondary files ===

    private String buildAiIndex(ProjectContext ctx, List<Skill> skills, boolean hasChecklists,
                                 boolean hasPrompts, boolean hasProjectNotes) {
        var sb = new StringBuilder();
        sb.append("# Project Index - ").append(ctx.name()).append("\n\n");
        sb.append("| File | Purpose |\n");
        sb.append("|------|---------|\n");
        sb.append("| `CLAUDE.md` | Main context file - start here |\n");
        sb.append("| `.ai/engineering-rules.md` | Engineering rules for this project |\n");
        sb.append("| `.ai/stack.md` | Stack details and dependency notes |\n");
        if (hasChecklists) sb.append("| `.ai/checklists.md` | Verification checklists |\n");
        if (hasPrompts) sb.append("| `.ai/prompts.md` | Reusable prompt templates |\n");
        if (hasProjectNotes) sb.append("| `.ai/project-notes.md` | Project-specific notes from local AI |\n");
        sb.append("| `.claude/skills/` | Curated workflow skills (invocable via `/<skill-id>`) |\n");
        if (!skills.isEmpty()) {
            sb.append("\n## Available Skills\n\n");
            skills.forEach(s -> sb.append("- `/").append(s.id()).append("` - ").append(
                s.trigger() == null ? "" : s.trigger()
            ).append("\n"));
        }
        return sb.toString();
    }

    private String buildStackMd(ProjectContext ctx) {
        var sb = new StringBuilder();
        sb.append("# Stack - ").append(ctx.name()).append("\n\n");
        sb.append("**Detected:** ").append(ctx.stack().displayName()).append("\n\n");
        if (ctx.stack().framework() != null) {
            sb.append("**Framework:** ").append(ctx.stack().framework()).append("\n\n");
        }
        sb.append("**Language:** ").append(ctx.stack().language()).append("\n");
        if (ctx.stack().buildTool() != null) {
            sb.append("**Build tool:** ").append(ctx.stack().buildTool()).append("\n");
        }
        sb.append("\n");

        if (!ctx.dependencies().isEmpty()) {
            sb.append("## Dependencies\n\n");
            ctx.dependencies().forEach(d -> sb.append("- ").append(d.display()).append("\n"));
        }

        if (!ctx.entryPoints().isEmpty()) {
            sb.append("\n## Entry Points\n\n");
            ctx.entryPoints().forEach((k, v) -> sb.append("- **").append(k).append(":** `").append(v).append("`\n"));
        }
        return sb.toString();
    }

    private static final String RULES_PLACEHOLDER =
        "_No engineering rules configured. Set `launchpad.standards.remote.url` in Launchpad settings, "
        + "or add `.launchpad/standards/rules.yml` to this project._\n";

    private static final String SKILLS_PLACEHOLDER =
        "_No workflow skills configured. Set `launchpad.standards.remote.url` in Launchpad settings, "
        + "or add `.launchpad/standards/skills.yml` to this project._\n";

    private String buildEngineeringRulesMd(List<Rule> rules) {
        var sb = new StringBuilder();
        sb.append("# Engineering Rules\n\n");
        sb.append("These rules apply to all work in this project, regardless of feature or task.\n\n");
        if (rules.isEmpty()) {
            sb.append(RULES_PLACEHOLDER);
            return sb.toString();
        }
        rules.forEach(rule -> {
            sb.append("## ").append(rule.title());
            if (rule.severity() != null && !rule.severity().isBlank()) {
                sb.append("  ·  ").append(rule.severity());
            }
            sb.append("\n\n");
            if (rule.description() != null && !rule.description().isBlank()) {
                sb.append(rule.description().strip()).append("\n\n");
            }
            if (rule.rationale() != null && !rule.rationale().isBlank()) {
                sb.append("_Why:_ ").append(rule.rationale().strip()).append("\n\n");
            }
        });
        return sb.toString();
    }

    private String buildChecklistsMd(List<Checklist> checklists) {
        var sb = new StringBuilder();
        sb.append("# Checklists\n\n");
        sb.append("Verification gates before declaring work done. Items marked with `*` are required.\n\n");
        checklists.forEach(c -> {
            sb.append("## ").append(c.title() != null ? c.title() : titleFromId(c.id())).append("\n\n");
            if (c.items() != null) {
                for (ChecklistItem item : c.items()) {
                    sb.append("- [ ] ").append(item.text());
                    if (item.required()) sb.append("  *");
                    sb.append("\n");
                }
            }
            sb.append("\n");
        });
        return sb.toString();
    }

    private String buildProjectNotesMd(ProjectContext ctx, String llmContent) {
        var sb = new StringBuilder();
        sb.append("# Project-Specific Notes - ").append(ctx.name()).append("\n\n");
        sb.append("Notes generated for this project by the local AI from the scanned codebase. ")
          .append("These complement the engineering rules and workflow skills in this folder.\n\n");
        sb.append(llmContent.strip()).append("\n");
        return sb.toString();
    }

    private String buildCursorProjectNotes(ProjectContext ctx, String llmContent) {
        var sb = new StringBuilder();
        sb.append("---\ndescription: Project-specific notes for ").append(ctx.name())
          .append("\nglobs: **/*\n---\n\n");
        sb.append(llmContent.strip()).append("\n");
        return sb.toString();
    }

    private String buildPromptsMd(List<Prompt> prompts) {
        var sb = new StringBuilder();
        sb.append("# Reusable Prompts\n\n");
        sb.append("Templates for common tasks. Substitute `{{placeholder}}` values before sending.\n\n");
        prompts.forEach(p -> {
            sb.append("## ").append(p.title() != null ? p.title() : titleFromId(p.id())).append("\n\n");
            if (p.template() != null && !p.template().isBlank()) {
                sb.append("```\n").append(p.template().strip()).append("\n```\n\n");
            }
        });
        return sb.toString();
    }

    // === Cursor secondary files ===

    private String buildCursorEngineeringRules(List<Rule> rules) {
        var sb = new StringBuilder();
        sb.append("---\ndescription: Engineering rules for this project\nglobs: **/*\n---\n\n");
        if (rules.isEmpty()) {
            sb.append(RULES_PLACEHOLDER);
            return sb.toString();
        }
        rules.forEach(rule -> {
            sb.append("- **").append(rule.title()).append("**");
            if (rule.severity() != null && !rule.severity().isBlank()) {
                sb.append(" (").append(rule.severity()).append(")");
            }
            sb.append(": ");
            if (rule.description() != null) sb.append(rule.description().replace('\n', ' ').strip());
            sb.append("\n");
            if (rule.rationale() != null && !rule.rationale().isBlank()) {
                sb.append("  _Why:_ ").append(rule.rationale().replace('\n', ' ').strip()).append("\n");
            }
        });
        return sb.toString();
    }

    private String buildCursorSkills(List<Skill> skills) {
        var sb = new StringBuilder();
        sb.append("---\ndescription: Curated workflow skills for this project\nglobs: **/*\n---\n\n");
        if (skills.isEmpty()) {
            sb.append(SKILLS_PLACEHOLDER);
            return sb.toString();
        }
        skills.forEach(s -> {
            sb.append("#### ").append(skillTitle(s)).append("\n\n");
            if (s.trigger() != null && !s.trigger().isBlank()) {
                sb.append("**Trigger:** ").append(s.trigger().strip()).append("\n\n");
            }
            if (s.steps() != null && !s.steps().isEmpty()) {
                sb.append("**Steps:**\n");
                int i = 1;
                for (var step : s.steps()) {
                    sb.append(i++).append(". ").append(step).append("\n");
                }
                sb.append("\n");
            }
            if (s.outputExpectations() != null && !s.outputExpectations().isEmpty()) {
                sb.append("**Expected Output:**\n");
                s.outputExpectations().forEach(o -> sb.append("- ").append(o).append("\n"));
                sb.append("\n");
            }
            if (s.notes() != null && !s.notes().isBlank()) {
                sb.append("**Notes:** ").append(s.notes().strip()).append("\n\n");
            }
        });
        return sb.toString();
    }

    private String buildCursorChecklists(List<Checklist> checklists) {
        var sb = new StringBuilder();
        sb.append("---\ndescription: Verification checklists for this project\nglobs: **/*\n---\n\n");
        checklists.forEach(c -> {
            sb.append("#### ").append(c.title() != null ? c.title() : titleFromId(c.id())).append("\n\n");
            if (c.items() != null) {
                for (ChecklistItem item : c.items()) {
                    sb.append("- [ ] ").append(item.text());
                    if (item.required()) sb.append("  *");
                    sb.append("\n");
                }
            }
            sb.append("\n");
        });
        return sb.toString();
    }

    private String buildCursorPrompts(List<Prompt> prompts) {
        var sb = new StringBuilder();
        sb.append("---\ndescription: Reusable prompt templates for this project\nglobs: **/*\n---\n\n");
        prompts.forEach(p -> {
            sb.append("#### ").append(p.title() != null ? p.title() : titleFromId(p.id())).append("\n\n");
            if (p.template() != null && !p.template().isBlank()) {
                sb.append("```\n").append(p.template().strip()).append("\n```\n\n");
            }
        });
        return sb.toString();
    }

    private String buildCursorStackRules(ProjectContext ctx) {
        return """
            ---
            description: Stack and dependency context
            globs: **/*
            ---

            Stack: %s

            Dependencies: %s
            """.formatted(
                ctx.stack().displayName(),
                ctx.dependencies().isEmpty()
                    ? "none detected"
                    : ctx.dependencies().stream()
                        .limit(15)
                        .map(com.acltabontabon.launchpad.scanner.Dependency::display)
                        .collect(java.util.stream.Collectors.joining(", "))
            );
    }

    // ── Workspace section (cross-project reference) ──────────────────────────

    /**
     * Appends a Workspace section listing every other Launchpad-managed project
     * on the machine, so an MCP-capable agent reading this file knows it can
     * reach sibling context without leaving the session. No-ops when there are
     * no siblings, so single-project users see no clutter.
     */
    private void appendWorkspaceSection(StringBuilder sb, ProjectContext ctx) {
        var section = renderWorkspaceSection(ctx);
        if (!section.isEmpty()) sb.append(section);
    }

    private String renderWorkspaceSection(ProjectContext ctx) {
        var current = Path.of(ctx.rootPath()).toAbsolutePath().normalize().toString();
        var siblings = projectRegistry.all().stream()
            .filter(p -> !current.equals(p.path()))
            .toList();
        if (siblings.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("## Workspace\n\n");
        sb.append("Other Launchpad-managed projects on this machine. An MCP client connected to ");
        sb.append("Launchpad's local server (`launchpad mcp`) can read their context without ");
        sb.append("leaving this session.\n\n");
        sb.append("| Project | Stack | Path |\n");
        sb.append("|---------|-------|------|\n");
        for (RegisteredProject p : siblings) {
            sb.append("| `").append(p.name()).append("` | ")
              .append(p.stack() == null ? "" : p.stack()).append(" | `")
              .append(p.path()).append("` |\n");
        }
        sb.append("\n**Cross-project MCP tools:**\n");
        sb.append("- `list_projects` - enumerate every registered project\n");
        sb.append("- `scan_project(project)` - stack, deps, packages, sample symbols\n");
        sb.append("- `get_standards(project)` - rules / skills / checklists that apply\n");
        sb.append("- `get_audit_findings(project)` - SARIF findings from last audit\n");
        sb.append("- `get_file(project, path)` / `list_files(project, glob)` - read source on demand\n");
        sb.append("- `compare_standards(a, b)` - common / a-only / b-only rules\n\n");
        sb.append("**Resources (tree-browsable):** `launchpad://projects`, ");
        sb.append("`launchpad://scan/{name}`, `launchpad://audit/{abs-path}`\n\n");
        return sb.toString();
    }
}
