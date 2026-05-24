package com.acltabontabon.launchpad.template.synthesis;

import com.acltabontabon.launchpad.ai.ContextGeneratorService;
import com.acltabontabon.launchpad.ai.SynthesisJob;
import com.acltabontabon.launchpad.ai.SynthesisValidator;
import com.acltabontabon.launchpad.scanner.ClassFact;
import com.acltabontabon.launchpad.scanner.PackageSummary;
import com.acltabontabon.launchpad.scanner.ProjectClassFacts;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.springboot.maven.MavenProfile;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import com.acltabontabon.launchpad.template.ArchitectureTreeRenderer;
import com.acltabontabon.launchpad.template.EndpointsTableRenderer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class SectionSynthesizer {

    @Nullable
    private final ContextGeneratorService generator;
    private final SynthesisPromptLoader synthesisPrompts = new SynthesisPromptLoader();

    public SectionSynthesizer(@Nullable ContextGeneratorService generator) {
        this.generator = generator;
    }

    public SynthesisOutputs synthesize(ProjectContext ctx) {
        var classFacts = ProjectClassFacts.collect(
            Path.of(ctx.rootPath()), ctx.sourceFiles(), ctx.endpoints());
        var allEndpoints = combinedEndpoints(ctx);
        var notes = endpointNotes(ctx, allEndpoints);

        return new SynthesisOutputs(
            introBody(ctx),
            architectureNarrative(ctx, classFacts),
            classFacts,
            allEndpoints,
            notes,
            buildProfilesBullets(ctx)
        );
    }

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
    private String architectureNarrative(ProjectContext ctx, List<ClassFact> facts) {
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
        var notes = new LinkedHashMap<String, String>(ctx.actuatorNotes());
        if (all.isEmpty()) return notes;

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

        var allowedKeys = new LinkedHashSet<String>();
        for (var ep : all) allowedKeys.add(EndpointsTableRenderer.key(ep));

        var job = new SynthesisJob(
            "endpoint-notes", template, SynthesisValidator.Shape.LINES,
            12000, 1200,
            () -> "",
            allowedKeys);
        var raw = runSynthesis(job);
        if (raw == null || raw.isBlank()) return notes;

        for (var rawLine : raw.split("\n")) {
            var line = rawLine.strip();
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

    // ── Allowlist builders ──────────────────────────────────────────────────

    private static Set<String> packageAllowlist(ProjectContext ctx) {
        var out = new LinkedHashSet<String>();
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

    private static Set<String> profileAllowlist(ProjectContext ctx) {
        var out = new LinkedHashSet<String>();
        for (var p : ctx.mavenProfiles()) {
            out.add(p.id());
            out.addAll(p.keyFlags());
        }
        return out;
    }

    private static Set<String> architectureAllowlist(List<ClassFact> facts) {
        var out = new LinkedHashSet<String>();
        for (var f : facts) {
            out.add(f.leafPackage());
            out.add(f.name());
            out.addAll(f.impls());
            out.addAll(f.routes());
        }
        return out;
    }

    /**
     * Allowlist for the project-intro paragraph. Permissive on purpose - the
     * intro is prose, so we cover the project name, framework, language,
     * package leaves, sample symbols, and any words longer than four chars
     * lifted from the README intro / pom description.
     */
    private static Set<String> introAllowlist(ProjectContext ctx) {
        var out = new LinkedHashSet<String>();
        out.add(ctx.name());
        if (ctx.stack().framework() != null) out.add(ctx.stack().framework());
        if (ctx.stack().language() != null) out.add(ctx.stack().language());
        if (ctx.stack().buildTool() != null) out.add(ctx.stack().buildTool());
        out.addAll(packageAllowlist(ctx));
        addLongWords(out, ctx.readmeIntro());
        addLongWords(out, ctx.pomDescription());
        return out;
    }

    private static Set<String> packageAllowlistWithSources(ProjectContext ctx) {
        var out = packageAllowlist(ctx);
        ctx.packageRepresentatives().values().forEach(body -> addIdentifierTokens(out, body));
        return out;
    }

    private static Set<String> profileAllowlistWithXml(ProjectContext ctx) {
        var out = profileAllowlist(ctx);
        ctx.profileRawXml().values().forEach(body -> addIdentifierTokens(out, body));
        return out;
    }

    private static final Pattern WORD_TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{3,}");

    private static void addLongWords(Set<String> sink, String text) {
        if (text == null || text.isEmpty()) return;
        var m = WORD_TOKEN.matcher(text);
        while (m.find()) sink.add(m.group());
    }

    private static final Pattern IDENTIFIER_TOKEN = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]{2,}\\b");

    private static void addIdentifierTokens(Set<String> sink, String text) {
        if (text == null || text.isEmpty()) return;
        var m = IDENTIFIER_TOKEN.matcher(text);
        while (m.find()) sink.add(m.group());
    }

    private String runSynthesis(SynthesisJob job) {
        // Without a generator (tests, AI disabled by Spring config) go straight to fallback.
        if (generator == null) return job.fallback().get();
        return generator.synthesize(job);
    }

    // ── Deterministic fallbacks ─────────────────────────────────────────────

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

    private static String renderActuatorHints(Map<String, String> hints) {
        if (hints == null || hints.isEmpty()) return "(none - no actuator endpoints detected)";
        var sb = new StringBuilder();
        hints.forEach((key, note) ->
            sb.append("  ").append(key).append(" => ")
              .append(note.isBlank() ? "(empty - self-explanatory)" : note).append("\n"));
        return sb.toString().stripTrailing();
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

    private static final class SynthesisPromptLoader {
        private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

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
}
