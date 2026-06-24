package com.acltabontabon.launchpad.mcp;

import com.acltabontabon.launchpad.audit.AuditService;
import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.config.RegisteredProject;
import com.acltabontabon.launchpad.model.ModelIdentity;
import com.acltabontabon.launchpad.model.Risk;
import com.acltabontabon.launchpad.model.SystemComponent;
import com.acltabontabon.launchpad.model.VirtualProjectContextStore;
import com.acltabontabon.launchpad.model.Workflow;
import com.acltabontabon.launchpad.model.graph.EdgeType;
import com.acltabontabon.launchpad.model.graph.NodeType;
import com.acltabontabon.launchpad.model.graph.ProjectEdge;
import com.acltabontabon.launchpad.model.graph.ProjectModel;
import com.acltabontabon.launchpad.model.graph.ProjectModelStore;
import com.acltabontabon.launchpad.model.graph.ProjectNode;
import com.acltabontabon.launchpad.scanner.doc.DocumentationIndex;
import com.acltabontabon.launchpad.scanner.doc.DocumentationPage;
import com.acltabontabon.launchpad.scanner.doc.Purpose;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.springboot.scanner.ProjectScanner;
import com.acltabontabon.launchpad.scanner.ProjectSupportDetector;
import com.acltabontabon.launchpad.scanner.ScanStore;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.IncompatiblePackSchemaException;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.task.RelevantStandards;
import com.acltabontabon.launchpad.task.TaskAdvisorService;
import com.acltabontabon.launchpad.task.TaskTurn;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposing Launchpad's project intelligence to any MCP client
 * (Claude Code, Cursor, Cline, Continue, Zed, ...). Deliberately scoped to
 * what is <em>unique</em> to Launchpad - scan results, applicable standards,
 * audit findings - so we don't duplicate the official
 * {@code mcp-server-filesystem} or {@code mcp-server-git}.
 * <p>
 * All read paths consult {@code <project>/.launchpad/} when fresh artifacts
 * exist (24h freshness window for the scan) and fall back to running the
 * underlying service when they don't. This means the MCP server works
 * standalone - a developer who never opens the TUI still gets value.
 * <p>
 * <b>Why no {@code @ConditionalOnProperty} gate:</b> Spring AOT (used by the
 * native build) evaluates conditions at build time. The {@code mcp} mode flag
 * is unset during AOT, so a conditional bean would be pruned from the native
 * image - leaving the MCP server with an empty tool list at runtime. Spring
 * AI's {@code AbstractAnnotatedMethodBeanFactoryInitializationAotProcessor}
 * walks live bean definitions at AOT and registers every {@code @McpTool}
 * parameter type for reflection automatically, so the bean MUST be present
 * during AOT. The MCP transport stays dormant in TUI mode because
 * {@code spring.ai.mcp.server.stdio} is set only by the {@code mcp} profile.
 */
@Component
public class LaunchpadMcpTools {

    private static final Logger log = LoggerFactory.getLogger(LaunchpadMcpTools.class);

    private static final Duration SCAN_FRESH_WINDOW = Duration.ofHours(24);

    // Per-page and per-project caps on find_documentation results. Keep MCP
    // responses bounded even when a query happens to hit every paragraph.
    private static final int FIND_MATCHES_PER_PAGE = 5;
    private static final int FIND_HITS_PER_PROJECT = 50;

    // Project-relative pointers used by references-mode payloads. The standards
    // companions carry one stable {#slug} anchor per item, so an agent can fetch
    // a single body via path + anchor; the sidecars are the machine-readable
    // projections of the same content.
    private static final String STANDARDS_INDEX = ".launchpad/standards.index.json";
    private static final String PROJECT_MODEL = ".launchpad/project.model.json";
    private static final String RULES_COMPANION = ".ai/engineering-rules.md";
    private static final String SKILLS_COMPANION = ".ai/skills.md";
    private static final String CHECKLISTS_COMPANION = ".ai/checklists.md";
    private static final String STANDARDS_FETCH_HINT =
        "Fetch full bodies by reading the cited project-relative path and anchor via the sandbox.";
    private static final String DOC_FETCH_HINT =
        "Read this project-relative path via the sandbox when full content is needed.";

    // Cap on the references-mode summary so a pointer payload never re-floods the
    // body it is meant to replace.
    private static final int SUMMARY_MAX_CHARS = 160;

    private final ProjectScanner scanner;
    private final ScanStore scanStore;
    private final AuditService auditService;
    private final StandardsLoader standardsLoader;
    private final ProjectRegistry projectRegistry;
    private final ProjectSupportDetector projectSupportDetector;
    private final VirtualProjectContextStore modelStore;
    private final ProjectModelStore graphStore;
    private final TaskAdvisorService taskAdvisor;
    private final long maxFileSizeBytes;
    private final McpResponseMode responseMode;

    // Server-side safety net for the stateless interview loop, mirroring the TUI
    // runner: the model returning DONE is the primary stop signal; these bound a
    // misbehaving model. The MCP client owns the loop and carries the transcript.
    private static final int MAX_INTERVIEW_ROUNDS = 8;
    private static final int MIN_ROUNDS_BEFORE_CRITIC = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    public LaunchpadMcpTools(ProjectScanner scanner,
                             ScanStore scanStore,
                             AuditService auditService,
                             StandardsLoader standardsLoader,
                             ProjectRegistry projectRegistry,
                             ProjectSupportDetector projectSupportDetector,
                             VirtualProjectContextStore modelStore,
                             ProjectModelStore graphStore,
                             TaskAdvisorService taskAdvisor,
                             @Value("${launchpad.scan.max-file-size-kb:512}") long maxFileSizeKb,
                             @Value("${launchpad.mcp.response-mode:}") String responseModeProperty) {
        this.scanner = scanner;
        this.scanStore = scanStore;
        this.auditService = auditService;
        this.standardsLoader = standardsLoader;
        this.projectRegistry = projectRegistry;
        this.projectSupportDetector = projectSupportDetector;
        this.modelStore = modelStore;
        this.graphStore = graphStore;
        this.taskAdvisor = taskAdvisor;
        this.maxFileSizeBytes = maxFileSizeKb * 1024L;
        // Read the env var explicitly so precedence (env > property > default) is
        // ours, not Spring's relaxed-binding. Warn once here, never per call.
        var rawEnv = System.getenv(McpResponseMode.ENV_VAR);
        if (McpResponseMode.isUnrecognized(rawEnv)) {
            log.warn("Ignoring unrecognized {}='{}' - using references/inline only.",
                McpResponseMode.ENV_VAR, rawEnv);
        } else if (McpResponseMode.isUnrecognized(responseModeProperty)) {
            log.warn("Ignoring unrecognized {}='{}' - using references/inline only.",
                McpResponseMode.PROPERTY, responseModeProperty);
        }
        this.responseMode = McpResponseMode.resolve(rawEnv, responseModeProperty);
    }

    /** Visible for tests: the response mode this instance resolved at construction. */
    McpResponseMode responseMode() {
        return responseMode;
    }

    private boolean inline() {
        return responseMode.isInline();
    }

    /**
     * Prepend the references-mode pointer to a model-backed tool payload. These
     * tools already return summaries (not prose bodies), so references mode only
     * adds a top-level pointer to the project-model sidecar where the full graph
     * lives - no fields are removed. A no-op in inline mode, preserving the legacy
     * shape exactly.
     */
    private void putModelPointer(Map<String, Object> out) {
        if (!inline()) {
            out.put("responseMode", "references");
            out.put("model", PROJECT_MODEL);
        }
    }

    @McpTool(
        name = "list_projects",
        description = "List every project the user has ever used Launchpad on (from the local registry "
            + "at ~/.launchpad/projects.json). Each entry carries a short name, absolute path, stack "
            + "label, timestamps, and any relationship metadata (tags / workspace / relatedTo) read "
            + "from the project's `.launchpad/project.yml`. Pass `workspace` to narrow the list to "
            + "one logical group - useful when the user has many projects but only a related cluster "
            + "matters for the current task. Use this to discover Launchpad-managed projects: call it "
            + "first when the user asks about \"my projects\" or doesn't remember a path. After selecting "
            + "a project, prefer your sandbox/context-mode for raw file and documentation inspection, and "
            + "use Launchpad for synthesized project knowledge, standards, audits, and project-model "
            + "intelligence - pass the chosen name to get_project_overview, get_standards, or "
            + "get_audit_findings via the `project` argument."
    )
    public Map<String, Object> listProjects(
        @McpArg(name = "workspace", description = "Optional workspace name to filter by",
            required = false)
        String workspace
    ) {
        var filter = workspace == null ? "" : workspace.trim();
        var entries = projectRegistry.all().stream()
            .filter(p -> filter.isEmpty() || filter.equalsIgnoreCase(p.workspace()))
            .map(LaunchpadMcpTools::registryEntryToMap)
            .toList();
        var out = new LinkedHashMap<String, Object>();
        out.put("projects", entries);
        out.put("count", entries.size());
        if (!filter.isEmpty()) out.put("workspaceFilter", filter);
        return out;
    }

    @McpTool(
        name = "scan_project",
        description = "Scan a project directory and return a structured summary of its stack, framework, "
            + "dependencies, package structure, and source file list. Returns a cached result when one "
            + "exists and is younger than 24 hours; otherwise runs a fresh scan. The `project` argument "
            + "accepts either a short name from the registry (see list_projects) or an absolute path. "
            + "If the client has a context-mode sandbox or direct filesystem access, prefer that path "
            + "for inspecting the build file and source tree directly. This tool primarily exists for "
            + "clients that cannot access project files directly - reach for the synthesized tools "
            + "(get_project_overview, get_systems, get_workflows) for the parts a sandbox cannot derive."
    )
    public Map<String, Object> scanProject(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var unsupported = requireSupported(projectRoot);
        if (unsupported != null) return unsupported;
        ProjectContext ctx;
        if (scanStore.isFresh(projectRoot, SCAN_FRESH_WINDOW)) {
            ctx = scanStore.load(projectRoot).orElseGet(() -> scanAndPersist(projectRoot));
        } else {
            ctx = scanAndPersist(projectRoot);
        }
        return Map.of(
            "name", ctx.name(),
            "rootPath", ctx.rootPath(),
            "stack", Map.of(
                "language", nullToEmpty(ctx.stack().language()),
                "buildTool", nullToEmpty(ctx.stack().buildTool()),
                "framework", nullToEmpty(ctx.stack().framework())
            ),
            "sourceFileCount", ctx.sourceFiles().size(),
            "dependencyCount", ctx.dependencies().size(),
            "packages", ctx.packageSummaries().stream()
                .map(p -> Map.of("path", p.path(), "fileCount", p.fileCount(),
                    "sampleSymbols", p.sampleSymbols()))
                .toList(),
            "dependencies", ctx.dependencies().stream()
                .map(d -> Map.of(
                    "name", nullToEmpty(d.name()),
                    "version", nullToEmpty(d.version()),
                    "scope", nullToEmpty(d.scope())))
                .toList()
        );
    }

    @McpTool(
        name = "get_standards",
        description = "Launchpad-distinctive standards and audit oracle. Prefer this tool for compliance, "
            + "standards interpretation, audit intelligence, drift analysis, and rule evaluation - a "
            + "generic sandbox cannot reliably reproduce this knowledge by re-reading source files. "
            + "Returns the engineering rules, skills, and checklists from the standards pack that "
            + "applies to a project. These are the same standards Launchpad embeds in AGENTS.md and the "
            + "`.ai/` companion tree, but as structured data so MCP clients can reason about them "
            + "programmatically. By default this returns references - each item's stable id, title, "
            + "content hash, a short summary, and a project-relative path + anchor into the `.ai/` "
            + "companion - so an in-session sandbox/indexer fetches full bodies on demand instead of "
            + "re-flooding context. Set LAUNCHPAD_MCP_RESPONSE_MODE=inline (or "
            + "launchpad.mcp.response-mode=inline) to inline full descriptions, rationales, steps, and "
            + "checklist items. The `project` argument accepts either a short name from the registry "
            + "(see list_projects) or an absolute path. Pass `ruleId` to fetch a single rule by its "
            + "stable id (as returned by a prior get_standards call or an audit finding's `ruleId`) "
            + "instead of the whole pack - an unknown id returns the list of available rule ids."
    )
    public Map<String, Object> getStandards(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project,
        @McpArg(name = "ruleId", description = "Optional stable rule id; when present, return only "
            + "that rule (reference or inline per response mode) instead of the full pack",
            required = false)
        String ruleId
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        try {
            var loadedRules = standardsLoader.loadRules(projectRoot);
            if (ruleId != null && !ruleId.isBlank()) {
                return singleRule(loadedRules, ruleId.trim());
            }
            var loadedSkills = standardsLoader.loadSkills(projectRoot);
            var loadedChecklists = standardsLoader.loadChecklists(projectRoot);
            if (inline()) {
                var rules = loadedRules.stream()
                    .map(r -> Map.of(
                        "id", nullToEmpty(r.id()),
                        "title", nullToEmpty(r.title()),
                        "severity", nullToEmpty(r.severity()),
                        "description", nullToEmpty(r.description()),
                        "rationale", nullToEmpty(r.rationale()),
                        "auditable", r.isAuditable()))
                    .toList();
                var skills = loadedSkills.stream()
                    .map(s -> Map.of(
                        "id", nullToEmpty(s.id()),
                        "title", nullToEmpty(s.title()),
                        "trigger", nullToEmpty(s.trigger()),
                        "steps", s.steps() == null ? List.of() : s.steps()))
                    .toList();
                var checklists = loadedChecklists.stream()
                    .map(c -> Map.of(
                        "id", nullToEmpty(c.id()),
                        "title", nullToEmpty(c.title()),
                        "items", c.items() == null ? List.of() : c.items().stream()
                            .map(i -> Map.of("id", nullToEmpty(i.id()), "text", nullToEmpty(i.text()),
                                "required", i.required()))
                            .toList()))
                    .toList();
                return Map.of(
                    "rules", rules,
                    "skills", skills,
                    "checklists", checklists
                );
            }
            var rules = loadedRules.stream().map(LaunchpadMcpTools::ruleRef).toList();
            var skills = loadedSkills.stream().map(LaunchpadMcpTools::skillRef).toList();
            var checklists = loadedChecklists.stream().map(LaunchpadMcpTools::checklistRef).toList();
            var out = new LinkedHashMap<String, Object>();
            out.put("responseMode", "references");
            out.put("index", STANDARDS_INDEX);
            out.put("companions", Map.of(
                "rules", RULES_COMPANION,
                "skills", SKILLS_COMPANION,
                "checklists", CHECKLISTS_COMPANION));
            out.put("hint", STANDARDS_FETCH_HINT);
            out.put("rules", rules);
            out.put("skills", skills);
            out.put("checklists", checklists);
            return out;
        } catch (IncompatiblePackSchemaException e) {
            return incompatiblePackSchema(e);
        }
    }

    /**
     * Single-rule projection for {@code get_standards(project, ruleId)}. Returns
     * the one rule (reference or inline per mode) or a not-found error that lists
     * the available rule ids so the caller can correct a typo without a second
     * round trip.
     */
    private Map<String, Object> singleRule(List<Rule> rules, String ruleId) {
        var match = rules.stream()
            .filter(r -> ruleId.equals(r.id()))
            .findFirst()
            .orElse(null);
        if (match == null) {
            var availableRuleIds = rules.stream()
                .map(Rule::id)
                .filter(java.util.Objects::nonNull)
                .toList();
            return McpError.notFound(
                "unknown_rule_id",
                "No rule with id '" + ruleId + "' in the standards that apply to this project.",
                "Call get_standards without ruleId to list every applicable rule, or pick an "
                    + "available id.")
                .withDetails(Map.of("requested", ruleId, "availableRuleIds", availableRuleIds))
                .toPayload();
        }
        var out = new LinkedHashMap<String, Object>();
        if (inline()) {
            out.put("rule", ruleToMap(match));
            return out;
        }
        out.put("responseMode", "references");
        out.put("index", STANDARDS_INDEX);
        out.put("companions", Map.of("rules", RULES_COMPANION));
        out.put("hint", STANDARDS_FETCH_HINT);
        out.put("rule", ruleRef(match));
        return out;
    }

    @McpTool(
        name = "get_audit_findings",
        description = "Launchpad-distinctive standards and audit oracle. Prefer this tool for compliance, "
            + "standards interpretation, audit intelligence, drift analysis, and rule evaluation - a "
            + "generic sandbox cannot reliably reproduce this knowledge by re-reading source files. "
            + "Returns standards audit findings for a project (rule violations detected by "
            + "Launchpad's local-AI + pattern audit engine). Reads .launchpad/audit.sarif.json when "
            + "present and runs a fresh audit otherwise. Each finding carries ruleId, ruleHash, "
            + "severity, filePath, line, and message - clients can filter the returned list by severity "
            + "(never|must|should|avoid) or ruleId. `ruleHash` is the content hash of the rule version "
            + "the finding was produced against; compare it with standards.index.json rules[*].contentHash "
            + "for the same ruleId to detect findings produced against stale standards text. The `project` "
            + "argument accepts either a short name from the registry (see list_projects) or an absolute path."
    )
    public Map<String, Object> getAuditFindings(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var unsupported = requireSupported(projectRoot);
        if (unsupported != null) return unsupported;
        try {
            var ctx = scanStore.load(projectRoot).orElseGet(() -> scanAndPersist(projectRoot));
            var result = auditService.run(ctx, projectRoot);
            var findings = result.findings().stream()
                .map(f -> Map.of(
                    "ruleId", nullToEmpty(f.ruleId()),
                    "ruleHash", nullToEmpty(f.ruleHash()),
                    "severity", nullToEmpty(f.severity()),
                    "ruleTitle", nullToEmpty(f.ruleTitle()),
                    "filePath", nullToEmpty(f.filePath()),
                    "line", f.line() == null ? 0 : f.line(),
                    "message", nullToEmpty(f.message()),
                    "evidence", nullToEmpty(f.evidence())))
                .toList();
            return Map.of(
                "rulesAudited", result.rulesAudited(),
                "totalFindings", result.findings().size(),
                "findings", findings,
                "sarifPath", result.sarifPath() == null ? "" : result.sarifPath().toString(),
                "markdownPath", result.markdownPath() == null ? "" : result.markdownPath().toString()
            );
        } catch (IncompatiblePackSchemaException e) {
            return incompatiblePackSchema(e);
        }
    }

    @McpTool(
        name = "compare_standards",
        description = "Launchpad-distinctive standards and audit oracle. Prefer this tool for compliance, "
            + "standards interpretation, audit intelligence, drift analysis, and rule evaluation - a "
            + "generic sandbox cannot reliably reproduce this knowledge by re-reading source files. "
            + "Diffs the standards (rules, skills, checklists) that apply to two registered "
            + "projects. Returns four buckets per kind: `common` (same id, same content on both "
            + "sides), `divergent` (same id, different content - both sides included for inspection), "
            + "`aOnly`, `bOnly`. Useful for spotting whether a recommendation that holds for project "
            + "A also holds for project B - especially after one project has overridden the shared "
            + "remote standards pack. By default each bucket entry is a reference (stable id, content "
            + "hash, short summary, and a project-relative path + anchor into the `.ai/` companion) "
            + "rather than the full body; set LAUNCHPAD_MCP_RESPONSE_MODE=inline (or "
            + "launchpad.mcp.response-mode=inline) to inline full rule/skill/checklist bodies."
    )
    public Map<String, Object> compareStandards(
        @McpArg(name = "projectA", description = "First project (name or absolute path)", required = true)
        String projectA,
        @McpArg(name = "projectB", description = "Second project (name or absolute path)", required = true)
        String projectB
    ) {
        var a = resolveProject(projectA);
        if (a instanceof Resolution.Error err) return err.payload();
        var b = resolveProject(projectB);
        if (b instanceof Resolution.Error err) return err.payload();
        var rootA = ((Resolution.Path) a).value();
        var rootB = ((Resolution.Path) b).value();

        try {
            // Only the per-entry projection differs between modes; the bucketing
            // (hash-based equality) is identical, so diffById is unchanged.
            Function<Rule, Map<String, Object>> ruleRender =
                inline() ? LaunchpadMcpTools::ruleToMap : LaunchpadMcpTools::ruleCompareRef;
            Function<Skill, Map<String, Object>> skillRender =
                inline() ? LaunchpadMcpTools::skillToMap : LaunchpadMcpTools::skillCompareRef;
            Function<Checklist, Map<String, Object>> checklistRender =
                inline() ? LaunchpadMcpTools::checklistToMap : LaunchpadMcpTools::checklistCompareRef;

            var rulesDiff = diffById(
                standardsLoader.loadRules(rootA), standardsLoader.loadRules(rootB),
                Rule::id, LaunchpadMcpTools::ruleHash, ruleRender);
            var skillsDiff = diffById(
                standardsLoader.loadSkills(rootA), standardsLoader.loadSkills(rootB),
                Skill::id, LaunchpadMcpTools::skillHash, skillRender);
            var checklistsDiff = diffById(
                standardsLoader.loadChecklists(rootA), standardsLoader.loadChecklists(rootB),
                Checklist::id, LaunchpadMcpTools::checklistHash, checklistRender);

            if (inline()) {
                return Map.of(
                    "projectA", rootA.toString(),
                    "projectB", rootB.toString(),
                    "rules", rulesDiff,
                    "skills", skillsDiff,
                    "checklists", checklistsDiff
                );
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("responseMode", "references");
            out.put("index", STANDARDS_INDEX);
            out.put("companions", Map.of(
                "rules", RULES_COMPANION,
                "skills", SKILLS_COMPANION,
                "checklists", CHECKLISTS_COMPANION));
            out.put("hint", STANDARDS_FETCH_HINT);
            out.put("projectA", rootA.toString());
            out.put("projectB", rootB.toString());
            out.put("rules", rulesDiff);
            out.put("skills", skillsDiff);
            out.put("checklists", checklistsDiff);
            return out;
        } catch (IncompatiblePackSchemaException e) {
            return incompatiblePackSchema(e);
        }
    }

    @McpTool(
        name = "get_workflows",
        description = "Launchpad-distinctive synthesized project knowledge. Prefer this over re-deriving "
            + "structure and relationships directly from source files. Returns the business and "
            + "operational workflows Launchpad discovered for a project - "
            + "what the service actually does. Each workflow carries a name, type (inbound API, scheduled, "
            + "event-driven, ...), trigger, steps, and the systems, external calls, and data stores it "
            + "touches. This is the synthesized answer from the persisted project model. "
            + "The `project` argument accepts either a short name from "
            + "the registry (see list_projects) or an absolute path. Run a scan first if no model exists yet."
    )
    public Map<String, Object> getWorkflows(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var model = modelStore.load(projectRoot).orElse(null);
        if (model == null) return noModelPayload(projectRoot);
        var workflows = model.workflows().stream()
            .map(LaunchpadMcpTools::workflowToMap)
            .toList();
        var out = new LinkedHashMap<String, Object>();
        putModelPointer(out);
        out.put("project", model.identity().name());
        out.put("rootPath", projectRoot.toString());
        out.put("workflowCount", workflows.size());
        out.put("workflows", workflows);
        return out;
    }

    private static Map<String, Object> workflowToMap(Workflow w) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(w.id()));
        m.put("name", nullToEmpty(w.name()));
        m.put("type", w.type() == null ? "" : w.type().name());
        m.put("trigger", nullToEmpty(w.trigger()));
        m.put("steps", w.steps() == null ? List.of() : w.steps());
        m.put("touchedSystems", w.touchedSystems() == null ? List.of() : w.touchedSystems());
        m.put("externalCalls", w.externalCalls() == null ? List.of() : w.externalCalls());
        m.put("dataEffects", w.dataEffects() == null ? List.of() : w.dataEffects());
        return m;
    }

    @McpTool(
        name = "get_systems",
        description = "Launchpad-distinctive synthesized project knowledge. Prefer this over re-deriving "
            + "structure and relationships directly from source files. Returns the logical subsystems "
            + "Launchpad identified for a project - the named "
            + "components, what each is responsible for, and which packages own it. Use this to orient "
            + "in an unfamiliar codebase before diving into files. Synthesized from the persisted project "
            + "model. The `project` argument accepts a short name (see list_projects) or an absolute path."
    )
    public Map<String, Object> getSystems(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var model = modelStore.load(projectRoot).orElse(null);
        if (model == null) return noModelPayload(projectRoot);
        var systems = model.systems().stream().map(LaunchpadMcpTools::systemToMap).toList();
        var out = new LinkedHashMap<String, Object>();
        putModelPointer(out);
        out.put("project", model.identity().name());
        out.put("rootPath", projectRoot.toString());
        out.put("systemCount", systems.size());
        out.put("systems", systems);
        return out;
    }

    @McpTool(
        name = "get_risks",
        description = "Launchpad-distinctive synthesized project knowledge. Prefer this over re-deriving "
            + "structure and relationships directly from source files. Returns the risks Launchpad "
            + "inferred for a project - concerns surfaced from observed "
            + "consistency (e.g. layering drift where a controller reaches a data store with no service in "
            + "between). Each risk carries a category, severity, description, affected systems, and a "
            + "suggested mitigation. These are inferred signals to verify, not confirmed defects. The "
            + "`project` argument accepts a short name (see list_projects) or an absolute path."
    )
    public Map<String, Object> getRisks(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var model = modelStore.load(projectRoot).orElse(null);
        if (model == null) return noModelPayload(projectRoot);
        var risks = model.risks().stream().map(LaunchpadMcpTools::riskToMap).toList();
        var out = new LinkedHashMap<String, Object>();
        putModelPointer(out);
        out.put("project", model.identity().name());
        out.put("rootPath", projectRoot.toString());
        out.put("riskCount", risks.size());
        out.put("risks", risks);
        return out;
    }

    @McpTool(
        name = "get_project_overview",
        description = "Launchpad-distinctive synthesized project knowledge. Prefer this over re-deriving "
            + "structure and relationships directly from source files. Returns the five-minute brief "
            + "for a project: the single call that answers \"what "
            + "would a senior engineer need to know to be productive here?\". Aggregates the synthesized "
            + "model - identity and stack, architecture style and layers, subsystem names, workflow names "
            + "by type, build commands, the top risks, and any suggested guardrails - with counts so the "
            + "caller can drill in via get_systems / get_workflows / get_risks. Prefer this as the first "
            + "call on an unfamiliar project. The `project` argument accepts a short name or absolute path."
    )
    public Map<String, Object> getProjectOverview(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var model = modelStore.load(projectRoot).orElse(null);
        if (model == null) return noModelPayload(projectRoot);

        var overview = new LinkedHashMap<String, Object>();
        putModelPointer(overview);
        overview.put("project", model.identity().name());
        overview.put("rootPath", projectRoot.toString());
        overview.put("stack", nullToEmpty(model.identity().primaryStack()));
        overview.put("generatedAt", nullToEmpty(model.identity().generatedAt()));

        var arch = new LinkedHashMap<String, Object>();
        arch.put("style", nullToEmpty(model.architecture().style()));
        arch.put("layers", model.architecture().layers());
        overview.put("architecture", arch);

        overview.put("systems", model.systems().stream().map(s -> nullToEmpty(s.name())).toList());

        // Workflow names grouped by type, so the brief reads "what it does" at a glance.
        var workflowsByType = new LinkedHashMap<String, List<String>>();
        for (var w : model.workflows()) {
            var type = w.type() == null ? "OTHER" : w.type().name();
            workflowsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(nullToEmpty(w.name()));
        }
        overview.put("workflowsByType", workflowsByType);

        overview.put("buildCommands", model.operations().buildCommands());
        overview.put("healthEndpoints", model.operations().healthEndpoints());

        overview.put("risks", model.risks().stream().map(r ->
            r.severity().name().toLowerCase() + " [" + nullToEmpty(r.category()) + "] "
                + nullToEmpty(r.description())).toList());
        overview.put("suggestedGuardrails", model.standards().inferredStandards().stream()
            .map(s -> nullToEmpty(s.proposedRule())).toList());

        overview.put("counts", Map.of(
            "systems", model.systems().size(),
            "workflows", model.workflows().size(),
            "risks", model.risks().size(),
            "suggestedGuardrails", model.standards().inferredStandards().size()
        ));
        return overview;
    }

    private static Map<String, Object> systemToMap(SystemComponent s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(s.id()));
        m.put("name", nullToEmpty(s.name()));
        m.put("responsibility", nullToEmpty(s.responsibility()));
        m.put("owningPackages", s.owningPackages() == null ? List.of() : s.owningPackages());
        m.put("entryPoints", s.entryPoints() == null ? List.of() : s.entryPoints());
        return m;
    }

    private static Map<String, Object> riskToMap(Risk r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(r.id()));
        m.put("category", nullToEmpty(r.category()));
        m.put("severity", r.severity() == null ? "" : r.severity().name());
        m.put("description", nullToEmpty(r.description()));
        m.put("affectedSystems", r.affectedSystems() == null ? List.of() : r.affectedSystems());
        m.put("suggestedMitigation", nullToEmpty(r.suggestedMitigation()));
        return m;
    }

    /** Shared no-model error so every model-backed tool reports the same way. */
    private Map<String, Object> noModelPayload(Path projectRoot) {
        return McpError.notFound(
            "no_project_model",
            "No project model found at " + projectRoot.resolve(".launchpad")
                + "/project-context.json.",
            "Scan the project first via scan_project."
        ).toPayload();
    }

    /**
     * Shared no-graph error for the deterministic-graph tools (get_repo_map /
     * get_architecture / get_task_context). Parallel to {@link #noModelPayload}
     * but points at {@code project.model.json} rather than the synthesized
     * {@code project-context.json}.
     */
    private Map<String, Object> noGraphPayload(Path projectRoot) {
        return McpError.notFound(
            "no_project_graph",
            "No project graph found at " + projectRoot.resolve(".launchpad")
                + "/project.model.json.",
            "Scan the project first via scan_project."
        ).toPayload();
    }

    /** Node types that form the module/package containment tree (get_repo_map). */
    private static final Set<NodeType> REPO_MAP_NODES = EnumSet.of(NodeType.PROJECT, NodeType.PACKAGE);
    /** Edge types that form the containment tree. */
    private static final Set<EdgeType> REPO_MAP_EDGES = EnumSet.of(EdgeType.CONTAINS);
    /** Node types that form the component/endpoint/dependency wiring (get_architecture). */
    private static final Set<NodeType> ARCH_NODES =
        EnumSet.of(NodeType.COMPONENT, NodeType.ENDPOINT, NodeType.ENTRYPOINT, NodeType.DEPENDENCY);
    /** Edge types that form the architecture wiring. */
    private static final Set<EdgeType> ARCH_EDGES =
        EnumSet.of(EdgeType.EXPOSES, EdgeType.DEPENDS_ON, EdgeType.IMPLEMENTS);
    /** Upper bound on the nodes a single get_task_context slice returns. */
    private static final int TASK_CONTEXT_NODE_CAP = 100;

    @McpTool(
        name = "get_repo_map",
        description = "Launchpad-distinctive semantic graph. Returns the module/package containment "
            + "tree from the deterministic project-model graph (.launchpad/project.model.json): the "
            + "`project` and `package` nodes and the `contains` edges that nest them. Each node carries "
            + "a stable id, a human-readable name, and a source ref (project-relative path, optional "
            + "line range). Use this to learn how the codebase is partitioned into modules before "
            + "drilling into files. Do NOT call this to list a directory or read files - your sandbox "
            + "does that natively and more cheaply; this returns the pre-derived semantic structure a "
            + "generic file walk cannot reproduce. The `project` argument accepts a short name (see "
            + "list_projects) or an absolute path. Run a scan first if no graph exists yet."
    )
    public Map<String, Object> getRepoMap(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project
    ) {
        return graphSlice(project, REPO_MAP_NODES, REPO_MAP_EDGES);
    }

    @McpTool(
        name = "get_architecture",
        description = "Launchpad-distinctive semantic graph. Returns the component wiring from the "
            + "deterministic project-model graph (.launchpad/project.model.json): the `component`, "
            + "`endpoint`, `entrypoint`, and `dependency` nodes and the `exposes` / `depends-on` / "
            + "`implements` edges between them. Each node carries a stable id, a name, and a source "
            + "ref. Use this to see which components expose which endpoints and what the project "
            + "depends on. Do NOT call this to read source files or grep for annotations - your "
            + "sandbox does that natively; this returns the pre-derived architecture graph. The "
            + "`project` argument accepts a short name (see list_projects) or an absolute path. Run a "
            + "scan first if no graph exists yet."
    )
    public Map<String, Object> getArchitecture(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project
    ) {
        return graphSlice(project, ARCH_NODES, ARCH_EDGES);
    }

    /**
     * Shared body for {@link #getRepoMap} and {@link #getArchitecture}: resolve
     * the project, load the deterministic graph, and project the nodes and edges
     * whose types fall in the requested sets. References mode adds the top-level
     * pointer to {@code project.model.json} (the nodes/edges are already compact
     * structured records, so no body is stripped - parity with get_systems et al).
     */
    private Map<String, Object> graphSlice(String project, Set<NodeType> nodeTypes,
                                           Set<EdgeType> edgeTypes) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var graph = graphStore.load(projectRoot).orElse(null);
        if (graph == null) return noGraphPayload(projectRoot);

        var nodes = graph.nodes().stream()
            .filter(n -> n.type() != null && nodeTypes.contains(n.type()))
            .map(LaunchpadMcpTools::nodeToMap)
            .toList();
        var edges = graph.edges().stream()
            .filter(e -> e.type() != null && edgeTypes.contains(e.type()))
            .map(LaunchpadMcpTools::edgeToMap)
            .toList();

        var out = new LinkedHashMap<String, Object>();
        putModelPointer(out);
        out.put("project", nullToEmpty(graph.projectName()));
        out.put("rootPath", projectRoot.toString());
        out.put("nodeCount", nodes.size());
        out.put("nodes", nodes);
        out.put("edgeCount", edges.size());
        out.put("edges", edges);
        return out;
    }

    @McpTool(
        name = "get_task_context",
        description = "Launchpad-distinctive semantic graph. Returns a focused slice of the "
            + "project-model graph for a task: the nodes whose id, name, or attributes match `query` "
            + "(case-insensitive), each of their direct (one-hop) neighbors, the edges connecting "
            + "them, and any standards rules whose title matches the query (as references). Use this "
            + "to pull just the relevant subgraph and applicable rules for a unit of work instead of "
            + "loading the whole model. Matched nodes are flagged `matched: true`; neighbors are "
            + "`matched: false`. The result is capped at " + TASK_CONTEXT_NODE_CAP + " nodes with a "
            + "`truncated` flag. Do NOT call this to full-text search source files - your sandbox "
            + "does that; this searches the derived graph and standards, not file contents. The "
            + "`project` argument accepts a short name or an absolute path."
    )
    public Map<String, Object> getTaskContext(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project,
        @McpArg(name = "query", description = "Keywords describing the task; matched case-insensitively "
            + "against node ids, names, and attribute values, and against standards rule titles",
            required = true)
        String query
    ) {
        if (query == null || query.isBlank()) {
            return McpError.invalidArgument(
                "missing_query",
                "The `query` argument is empty.",
                "Pass a non-empty task description or keywords to slice the graph by."
            ).toPayload();
        }
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var graph = graphStore.load(projectRoot).orElse(null);
        if (graph == null) return noGraphPayload(projectRoot);

        var needle = query.toLowerCase(java.util.Locale.ROOT);

        // 1. Direct matches against the graph nodes.
        var matchedIds = new LinkedHashSet<String>();
        for (var n : graph.nodes()) {
            if (nodeMatches(n, needle)) matchedIds.add(nullToEmpty(n.id()));
        }

        // 2. One-hop neighbors plus the edges that connect them to a match.
        var keepIds = new LinkedHashSet<String>(matchedIds);
        var keptEdges = new ArrayList<ProjectEdge>();
        for (var e : graph.edges()) {
            var fromMatched = matchedIds.contains(e.from());
            var toMatched = matchedIds.contains(e.to());
            if (fromMatched || toMatched) {
                keptEdges.add(e);
                keepIds.add(e.from());
                keepIds.add(e.to());
            }
        }

        // 3. Project the kept nodes (capped), flagging which were direct matches.
        var nodesById = new LinkedHashMap<String, ProjectNode>();
        for (var n : graph.nodes()) nodesById.put(nullToEmpty(n.id()), n);
        var nodes = new ArrayList<Map<String, Object>>();
        boolean truncated = false;
        for (var id : keepIds) {
            if (nodes.size() >= TASK_CONTEXT_NODE_CAP) { truncated = true; break; }
            var n = nodesById.get(id);
            if (n == null) continue;
            var m = nodeToMap(n);
            m.put("matched", matchedIds.contains(id));
            nodes.add(m);
        }
        var keptNodeIds = nodes.stream().map(m -> (String) m.get("id")).collect(
            java.util.stream.Collectors.toSet());
        var edges = keptEdges.stream()
            .filter(e -> keptNodeIds.contains(e.from()) && keptNodeIds.contains(e.to()))
            .map(LaunchpadMcpTools::edgeToMap)
            .toList();

        // 4. Related standards: rules whose title matches the query, as references.
        List<Map<String, Object>> relatedStandards;
        try {
            relatedStandards = standardsLoader.loadRules(projectRoot).stream()
                .filter(r -> r.title() != null
                    && r.title().toLowerCase(java.util.Locale.ROOT).contains(needle))
                .map(LaunchpadMcpTools::ruleRef)
                .toList();
        } catch (IncompatiblePackSchemaException e) {
            // Standards are a best-effort enrichment here; a format-incompatible
            // pack must not sink the graph slice the caller actually asked for.
            relatedStandards = List.of();
        }

        var out = new LinkedHashMap<String, Object>();
        putModelPointer(out);
        out.put("project", nullToEmpty(graph.projectName()));
        out.put("rootPath", projectRoot.toString());
        out.put("query", query);
        out.put("matchedCount", matchedIds.size());
        out.put("nodeCount", nodes.size());
        out.put("nodes", nodes);
        out.put("edgeCount", edges.size());
        out.put("edges", edges);
        out.put("relatedStandards", relatedStandards);
        out.put("truncated", truncated);
        return out;
    }

    /** Case-insensitive match of a node's id, name, and attribute values against {@code needle}. */
    private static boolean nodeMatches(ProjectNode n, String needle) {
        if (n.id() != null && n.id().toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
        if (n.name() != null && n.name().toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
        if (n.attributes() != null) {
            for (var v : n.attributes().values()) {
                if (v != null && v.toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
            }
        }
        return false;
    }

    /** JSON-friendly projection of a graph node: stable id, type, name, source ref, attributes. */
    private static Map<String, Object> nodeToMap(ProjectNode n) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(n.id()));
        m.put("type", n.type() == null ? "" : n.type().json());
        m.put("name", nullToEmpty(n.name()));
        if (n.source() != null) {
            var s = new LinkedHashMap<String, Object>();
            s.put("path", nullToEmpty(n.source().path()));
            if (n.source().startLine() != null) s.put("startLine", n.source().startLine());
            if (n.source().endLine() != null) s.put("endLine", n.source().endLine());
            m.put("source", s);
        }
        if (n.attributes() != null && !n.attributes().isEmpty()) {
            m.put("attributes", n.attributes());
        }
        return m;
    }

    /** JSON-friendly projection of a graph edge: from id, to id, kebab-cased type. */
    private static Map<String, Object> edgeToMap(ProjectEdge e) {
        var m = new LinkedHashMap<String, Object>();
        m.put("from", nullToEmpty(e.from()));
        m.put("to", nullToEmpty(e.to()));
        m.put("type", e.type() == null ? "" : e.type().json());
        return m;
    }

    @McpTool(
        name = "lint_standards",
        description = "STUB - not yet available (tracked in issue #16). When implemented, this will "
            + "validate a standards-pack YAML file against the schema and report errors before the "
            + "pack is published, so a malformed pack never reaches a project. Do not call this yet; "
            + "it returns a `not_yet_available` error. The surface is registered so it lights up the "
            + "moment the dependency lands."
    )
    public Map<String, Object> lintStandards(
        @McpArg(name = "path", description = "Path to the standards-pack file or directory to lint",
            required = false)
        String path
    ) {
        return McpError.unsupported(
            "not_yet_available",
            "lint_standards is not yet available; it is tracked in issue #16.",
            "Watch issue #16 for availability."
        ).withDetails(Map.of("trackingIssue", 16)).toPayload();
    }

    @McpTool(
        name = "audit_findings_diff",
        description = "STUB - not yet available (tracked in issue #21). When implemented, this will "
            + "diff the current audit findings against a saved baseline so a caller sees only the "
            + "new or resolved violations (with per-finding suppression). Do not call this yet; it "
            + "returns a `not_yet_available` error. The surface is registered so it lights up the "
            + "moment the dependency lands."
    )
    public Map<String, Object> auditFindingsDiff(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project,
        @McpArg(name = "baseline", description = "Optional path to a baseline audit snapshot to diff "
            + "against", required = false)
        String baseline
    ) {
        return McpError.unsupported(
            "not_yet_available",
            "audit_findings_diff is not yet available; it is tracked in issue #21.",
            "Watch issue #21 for availability."
        ).withDetails(Map.of("trackingIssue", 21)).toPayload();
    }

    @McpTool(
        name = "ask_task_question",
        description = "Launchpad-distinctive task interview. Exposes the local-AI `/new-task` "
            + "interview over MCP so a cloud agent can reduce ambiguity in a task BEFORE it starts "
            + "work, using the project's own engineering standards as the question source. Returns the "
            + "next clarifying question (`done: false`) or signals the interview is complete "
            + "(`done: true`), at which point you call finalize_task. The interview is stateless: pass "
            + "the full transcript so far as `history` (a JSON array of {question, answer} objects) and "
            + "append each answered turn before the next call. Do NOT call this for trivial or "
            + "unambiguous tasks - it adds round-trips; use it when a task is underspecified. Requires "
            + "a local AI provider (e.g. Ollama) to be running. The `project` argument accepts a short "
            + "name (see list_projects) or an absolute path."
    )
    public Map<String, Object> askTaskQuestion(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project,
        @McpArg(name = "task", description = "The task description the user wants to scope", required = true)
        String task,
        @McpArg(name = "history", description = "Interview transcript so far as a JSON array of "
            + "{\"question\":..., \"answer\":...} objects; omit or pass [] for the first question",
            required = false)
        String history
    ) {
        if (task == null || task.isBlank()) return missingTask();
        var prep = prepareTask(project);
        if (prep.error() != null) return prep.error();
        List<TaskTurn> turns;
        try {
            turns = parseHistory(history);
        } catch (RuntimeException e) {
            return malformedHistory(e);
        }
        try {
            var standards = selectStandards(prep.root(), prep.ctx(), task, turns);
            // Server-side runaway guard: past the cap, stop asking and let the
            // caller finalize even if the model would keep probing.
            if (turns.size() >= MAX_INTERVIEW_ROUNDS) {
                return donePayload(turns, "round cap reached (" + MAX_INTERVIEW_ROUNDS + ")");
            }
            // No applicable standards means there is nothing standards-driven to
            // interview about; the synthesised brief still benefits from the scan.
            if (standards.rules().isEmpty() && standards.skills().isEmpty()
                    && standards.checklists().isEmpty()) {
                return donePayload(turns, "no engineering standards apply to this task");
            }
            var stack = prep.ctx().stack();
            var response = taskAdvisor.askNextQuestion(stack, task, turns,
                standards.rules(), standards.skills(), standards.checklists());
            if (response.equals(TaskAdvisorService.DONE_TOKEN)
                    || response.contains(TaskAdvisorService.DONE_TOKEN)) {
                // The interview model declared the brief done. Run the second-opinion
                // critic once the transcript is substantial enough; a surfaced gap
                // becomes the next question. Near-duplicate follow-ups collapse to
                // READY inside the planner, so this converges.
                if (turns.size() >= MIN_ROUNDS_BEFORE_CRITIC) {
                    var verdict = taskAdvisor.critiqueReadiness(stack, task, turns,
                        standards.rules(), standards.skills(), standards.checklists());
                    if (!verdict.equals(TaskAdvisorService.READY_TOKEN)) {
                        return questionPayload(verdict, turns, "critic");
                    }
                }
                return donePayload(turns, "interview complete");
            }
            return questionPayload(response, turns, "interview");
        } catch (IncompatiblePackSchemaException e) {
            return incompatiblePackSchema(e);
        } catch (RuntimeException e) {
            return llmError("interview", e);
        }
    }

    @McpTool(
        name = "finalize_task",
        description = "Launchpad-distinctive task interview. Synthesises the final, standards-grounded "
            + "task brief from a `/new-task` interview transcript: a markdown document with Goal, "
            + "Constraints (drawn deterministically from the applicable rules), Acceptance criteria, "
            + "Out of scope, and Standards consulted. Call this once ask_task_question returns "
            + "`done: true` (or immediately for a well-specified task with an empty `history`). Returns "
            + "the markdown plus any validator warnings. Do NOT use this to summarise arbitrary text - "
            + "it is scoped to the interview transcript and the project's standards. Requires a local "
            + "AI provider to be running."
    )
    public Map<String, Object> finalizeTask(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project,
        @McpArg(name = "task", description = "The task description being scoped", required = true)
        String task,
        @McpArg(name = "history", description = "Interview transcript as a JSON array of "
            + "{\"question\":..., \"answer\":...} objects; omit or pass [] to synthesise from the task "
            + "description alone", required = false)
        String history
    ) {
        if (task == null || task.isBlank()) return missingTask();
        var prep = prepareTask(project);
        if (prep.error() != null) return prep.error();
        List<TaskTurn> turns;
        try {
            turns = parseHistory(history);
        } catch (RuntimeException e) {
            return malformedHistory(e);
        }
        try {
            var standards = selectStandards(prep.root(), prep.ctx(), task, turns);
            var result = taskAdvisor.synthesise(prep.ctx(), task, turns,
                standards.rules(), standards.skills(), standards.checklists(), null);
            var out = new LinkedHashMap<String, Object>();
            out.put("project", prep.ctx().name());
            out.put("rootPath", prep.root().toString());
            out.put("turnCount", turns.size());
            out.put("markdown", result.markdown());
            out.put("warnings", result.warnings() == null ? List.of() : result.warnings());
            return out;
        } catch (IncompatiblePackSchemaException e) {
            return incompatiblePackSchema(e);
        } catch (RuntimeException e) {
            return llmError("synthesis", e);
        }
    }

    /** Heading rendered by {@link com.acltabontabon.launchpad.task.MarkdownPostProcessor} per section key. */
    private static final Map<String, String> SECTION_HEADINGS = Map.of(
        "goal", "## Goal",
        "acceptance", "## Acceptance criteria",
        "out_of_scope", "## Out of scope");

    @McpTool(
        name = "regenerate_section",
        description = "Launchpad-distinctive task interview. Re-rolls a single section of the "
            + "synthesised task brief - `goal`, `acceptance`, or `out_of_scope` - without re-running "
            + "the whole interview, so a caller can refine one weak section in isolation. Returns the "
            + "regenerated section's markdown. Do NOT call this before finalize_task has produced a "
            + "brief, and do not use it to edit the Constraints / Standards-consulted sections - those "
            + "are generated deterministically from the standards pack, not the model. Requires a "
            + "local AI provider to be running."
    )
    public Map<String, Object> regenerateSection(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project,
        @McpArg(name = "section", description = "Which section to regenerate: goal, acceptance, or "
            + "out_of_scope", required = true)
        String section,
        @McpArg(name = "task", description = "The task description being scoped", required = true)
        String task,
        @McpArg(name = "history", description = "Interview transcript as a JSON array of "
            + "{\"question\":..., \"answer\":...} objects", required = false)
        String history
    ) {
        if (task == null || task.isBlank()) return missingTask();
        var key = section == null ? "" : section.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        var heading = SECTION_HEADINGS.get(key);
        if (heading == null) {
            return McpError.invalidArgument(
                "invalid_section",
                "Unknown section `" + section + "`.",
                "Allowed values: goal, acceptance, out_of_scope."
            ).toPayload();
        }
        var prep = prepareTask(project);
        if (prep.error() != null) return prep.error();
        List<TaskTurn> turns;
        try {
            turns = parseHistory(history);
        } catch (RuntimeException e) {
            return malformedHistory(e);
        }
        try {
            var standards = selectStandards(prep.root(), prep.ctx(), task, turns);
            var result = taskAdvisor.synthesise(prep.ctx(), task, turns,
                standards.rules(), standards.skills(), standards.checklists(), null);
            var extracted = extractMarkdownSection(result.markdown(), heading);
            var out = new LinkedHashMap<String, Object>();
            out.put("project", prep.ctx().name());
            out.put("section", key);
            out.put("heading", heading);
            out.put("markdown", extracted);
            out.put("warnings", result.warnings() == null ? List.of() : result.warnings());
            return out;
        } catch (IncompatiblePackSchemaException e) {
            return incompatiblePackSchema(e);
        } catch (RuntimeException e) {
            return llmError("synthesis", e);
        }
    }

    // ---- interview tool shared helpers -------------------------------------

    /** Resolved project root + a loaded/scanned context, or a ready-to-return error payload. */
    private record TaskPrep(Path root, ProjectContext ctx, Map<String, Object> error) {}

    /**
     * Resolve the project, gate on Spring Boot support, and load (or run) the
     * scan the interview/synthesis needs for stack and constraints. Returns a
     * {@link TaskPrep} whose {@code error} is non-null when any step failed.
     */
    private TaskPrep prepareTask(String project) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return new TaskPrep(null, null, err.payload());
        var projectRoot = ((Resolution.Path) resolved).value();
        var unsupported = requireSupported(projectRoot);
        if (unsupported != null) return new TaskPrep(null, null, unsupported);
        return new TaskPrep(projectRoot, loadOrScan(projectRoot), null);
    }

    /** Load the full standards pack and scope-filter it for this task (shared by all three tools). */
    private RelevantStandards selectStandards(Path projectRoot, ProjectContext ctx,
                                              String task, List<TaskTurn> turns) {
        var rules = standardsLoader.loadRules(projectRoot);
        var skills = standardsLoader.loadSkills(projectRoot);
        var checklists = standardsLoader.loadChecklists(projectRoot);
        return TaskAdvisorService.selectRelevantStandards(
            ctx == null ? null : ctx.stack(), task, turns, rules, skills, checklists);
    }

    /** Deserialize the `history` JSON array into turns; empty list for null/blank. */
    private static List<TaskTurn> parseHistory(String historyJson) {
        if (historyJson == null || historyJson.isBlank()) return List.of();
        try {
            var turns = JSON.readValue(historyJson, new TypeReference<List<TaskTurn>>() {});
            return turns == null ? List.of() : turns;
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static Map<String, Object> missingTask() {
        return McpError.invalidArgument(
            "missing_task",
            "The `task` argument is empty.",
            "Pass the task description to scope."
        ).toPayload();
    }

    private static Map<String, Object> malformedHistory(RuntimeException e) {
        return McpError.invalidArgument(
            "malformed_history",
            "Could not parse `history` as a JSON array of {question, answer} objects: " + e.getMessage(),
            "Pass a JSON array like [{\"question\":\"...\",\"answer\":\"...\"}], or omit it for the "
                + "first turn."
        ).toPayload();
    }

    /** Uniform error when a local-model call fails (provider down, model missing, timeout). */
    private static Map<String, Object> llmError(String phase, RuntimeException e) {
        return McpError.internal(
            "llm_" + phase + "_failed",
            "The local model call failed during " + phase + ": " + e.getMessage()
        ).withDetails(Map.of("hint", "Ensure the local AI provider (e.g. Ollama at "
            + "http://localhost:11434) is running and the configured model is pulled.")).toPayload();
    }

    private static Map<String, Object> donePayload(List<TaskTurn> turns, String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("done", true);
        out.put("round", turns.size());
        out.put("reason", reason);
        out.put("hint", "Call finalize_task with the same task and history to synthesise the brief.");
        return out;
    }

    private static Map<String, Object> questionPayload(String question, List<TaskTurn> turns,
                                                       String source) {
        var out = new LinkedHashMap<String, Object>();
        out.put("done", false);
        out.put("question", question);
        out.put("round", turns.size() + 1);
        out.put("maxRounds", MAX_INTERVIEW_ROUNDS);
        out.put("source", source);
        out.put("hint", "Answer the question, append {question, answer} to history, then call "
            + "ask_task_question again - or finalize_task when you have enough context.");
        return out;
    }

    /**
     * Slice one {@code ## Heading} section out of the assembled brief: the lines
     * after the heading up to the next {@code ## } heading (or end). Empty string
     * when the section is absent (e.g. an empty Out-of-scope is omitted entirely).
     */
    private static String extractMarkdownSection(String markdown, String heading) {
        if (markdown == null || markdown.isBlank()) return "";
        var lines = markdown.split("\\R", -1);
        var sb = new StringBuilder();
        boolean inSection = false;
        for (var line : lines) {
            if (line.strip().equals(heading)) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("## ")) break;
            if (inSection) sb.append(line).append("\n");
        }
        return sb.toString().strip();
    }

    @McpTool(
        name = "list_documentation",
        description = "List the Markdown and AsciiDoc pages discovered for a registered project. "
            + "Returns a flat ordered page list - each page carries its project-relative path, "
            + "extracted title, page format (MARKDOWN / ASCIIDOC), and a coarse purpose tag "
            + "(OVERVIEW / SETUP / ARCHITECTURE / API / OPERATIONS / CONTRIBUTION / CHANGELOG / "
            + "UNKNOWN). Reads from the cached scan and falls back to running a fresh scan when "
            + "stale. The `project` argument accepts either a short name from the registry (see "
            + "list_projects) or an absolute path. Pass `purpose` to narrow the result to one "
            + "bucket - e.g. `purpose: \"setup\"` to retrieve the project's getting-started docs. "
            + "If the client has a context-mode sandbox or direct filesystem access, prefer that path "
            + "for document discovery and retrieval. This tool primarily exists for clients that cannot "
            + "access project files directly."
    )
    public Map<String, Object> listDocumentation(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project,
        @McpArg(name = "purpose", description = "Optional purpose filter (case-insensitive). One of: "
            + "overview, setup, architecture, api, operations, contribution, changelog, unknown.",
            required = false)
        String purpose
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var unsupported = requireSupported(projectRoot);
        if (unsupported != null) return unsupported;
        var ctx = loadOrScan(projectRoot);
        Purpose filter = parsePurposeFilter(purpose);
        if (purpose != null && !purpose.isBlank() && filter == null) {
            return McpError.invalidArgument(
                "invalid_purpose",
                "Unknown purpose `" + purpose + "`.",
                "Allowed values: overview, setup, architecture, api, operations, contribution, "
                    + "changelog, unknown."
            ).toPayload();
        }
        return documentationToMap(ctx, projectRoot, filter);
    }

    @McpTool(
        name = "find_documentation",
        description = "Search documentation pages by case-insensitive keyword. Reads each candidate "
            + "page's content lazily (subject to launchpad.scan.max-file-size-kb) and returns matches "
            + "with the line number, the matching line, and a small excerpt. When `project` is given, "
            + "scopes the search to that project; when omitted, iterates every registered project so "
            + "Claude can discover library docs even when only the consuming service was named. "
            + "Results are grouped by project. Each project contributes at most "
            + FIND_HITS_PER_PROJECT + " matches; the overall match count and a `truncated` flag let "
            + "the caller decide whether to refine the query. "
            + "If the client has a context-mode sandbox or direct filesystem access, prefer that path "
            + "for document discovery and retrieval. This tool primarily exists for clients that cannot "
            + "access project files directly."
    )
    public Map<String, Object> findDocumentation(
        @McpArg(name = "query", description = "Keyword to search for (case-insensitive substring)",
            required = true)
        String query,
        @McpArg(name = "project", description = "Optional project name or absolute path; when omitted "
            + "every registered project is searched", required = false)
        String project
    ) {
        if (query == null || query.isBlank()) {
            return McpError.invalidArgument(
                "missing_query",
                "The `query` argument is empty.",
                "Pass a non-empty substring to search for."
            ).toPayload();
        }
        String needle = query.toLowerCase();

        List<Path> roots = new ArrayList<>();
        if (project != null && !project.isBlank()) {
            var resolved = resolveProject(project);
            if (resolved instanceof Resolution.Error err) return err.payload();
            var projectRoot = ((Resolution.Path) resolved).value();
            var unsupported = requireSupported(projectRoot);
            if (unsupported != null) return unsupported;
            roots.add(projectRoot);
        } else {
            for (var entry : projectRegistry.all()) {
                var p = projectRegistry.resolveToPath(entry.name());
                p.ifPresent(roots::add);
            }
        }

        var groups = new ArrayList<Map<String, Object>>();
        int totalHits = 0;
        boolean anyTruncated = false;
        for (var root : roots) {
            var ctx = scanStore.load(root).orElse(null);
            if (ctx == null) continue;
            var docs = ctx.documentation();
            if (docs == null || docs.isEmpty()) continue;
            var perProject = new ArrayList<Map<String, Object>>();
            for (DocumentationPage page : docs.pages()) {
                if (perProject.size() >= FIND_HITS_PER_PROJECT) {
                    anyTruncated = true;
                    break;
                }
                var matches = matchesIn(root.resolve(page.path()), needle);
                for (var m : matches) {
                    if (perProject.size() >= FIND_HITS_PER_PROJECT) {
                        anyTruncated = true;
                        break;
                    }
                    var hit = new LinkedHashMap<String, Object>();
                    hit.put("path", page.path());
                    hit.put("title", nullToEmpty(page.title()));
                    hit.put("format", page.format().name());
                    hit.put("purpose", page.purpose().name());
                    hit.put("line", m.line());
                    hit.put("excerpt", m.excerpt());
                    perProject.add(hit);
                }
            }
            if (perProject.isEmpty()) continue;
            var group = new LinkedHashMap<String, Object>();
            group.put("project", ctx.name());
            group.put("rootPath", root.toString());
            group.put("matchCount", perProject.size());
            group.put("matches", perProject);
            groups.add(group);
            totalHits += perProject.size();
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("query", query);
        out.put("projectsSearched", roots.size());
        out.put("totalMatches", totalHits);
        out.put("truncated", anyTruncated);
        out.put("perProjectLimit", FIND_HITS_PER_PROJECT);
        out.put("results", groups);
        return out;
    }

    @McpTool(
        name = "get_documentation",
        description = "Read a single Markdown or AsciiDoc page from a registered project. The "
            + "`path` must match a page surfaced by list_documentation - reads outside the "
            + "detected docs set are rejected, even when the file exists, so the AI cannot use "
            + "this tool as a generic file reader. Inherits the binary, size, and traversal guards "
            + "from the file-read path. By default this returns a reference - the page's "
            + "project-relative path, title, format, purpose, size, and content hash - and omits the "
            + "body, so a sandbox reads the file directly instead of paying for it twice. Set "
            + "LAUNCHPAD_MCP_RESPONSE_MODE=inline (or launchpad.mcp.response-mode=inline) to inline "
            + "the full page content. The `project` argument accepts either a short name or an "
            + "absolute path. If the client has a context-mode sandbox or direct filesystem access, "
            + "prefer that path for document retrieval. This tool primarily exists for clients that "
            + "cannot access project files directly."
    )
    public Map<String, Object> getDocumentation(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project,
        @McpArg(name = "path", description = "Project-relative path to a doc page (as listed by "
            + "list_documentation)", required = true)
        String path
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var unsupported = requireSupported(projectRoot);
        if (unsupported != null) return unsupported;

        if (path == null || path.isBlank()) {
            return McpError.invalidArgument(
                "missing_path",
                "The `path` argument is empty.",
                "Pass a project-relative doc-page path as `path`."
            ).toPayload();
        }

        var ctx = loadOrScan(projectRoot);
        var docs = ctx.documentation();
        if (docs == null || docs.isEmpty()) {
            return McpError.notFound(
                "no_documentation",
                "No documentation detected for this project.",
                "Call list_documentation first to confirm there are pages to read."
            ).toPayload();
        }
        DocumentationPage page = null;
        for (var p : docs.pages()) {
            if (p.path().equals(path)) { page = p; break; }
        }
        if (page == null) {
            return McpError.permissionDenied(
                "path_not_in_docs_index",
                "The path is not part of the detected docs set for this project."
            ).withDetails(Map.of("requested", path))
                .toPayload();
        }

        Path target;
        try {
            target = projectRoot.resolve(path).normalize();
        } catch (RuntimeException e) {
            return McpError.invalidArgument(
                "invalid_path",
                "Path could not be resolved: " + e.getMessage()
            ).toPayload();
        }
        if (!target.startsWith(projectRoot)) {
            return McpError.permissionDenied(
                "path_escapes_project",
                "The resolved path is outside the project root - refusing to read."
            ).toPayload();
        }
        if (!Files.isRegularFile(target)) {
            return McpError.invalidArgument(
                "not_a_file",
                "No regular file at " + path + " under the project root."
            ).toPayload();
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            return McpError.internal(
                "stat_failed",
                "Could not stat " + path + ": " + e.getMessage()
            ).toPayload();
        }
        if (size > maxFileSizeBytes) {
            return new McpError(
                "file_too_large",
                McpError.Type.RESOURCE_EXHAUSTED,
                "Doc page is " + size + " bytes; limit is " + maxFileSizeBytes + ".",
                "Raise launchpad.scan.max-file-size-kb if larger pages must be readable."
            ).toPayload();
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(target);
        } catch (IOException e) {
            return McpError.internal(
                "read_failed",
                "Could not read " + path + ": " + e.getMessage()
            ).toPayload();
        }
        if (looksBinary(bytes)) {
            return McpError.unsupported(
                "file_appears_binary",
                "File contains NUL bytes in its first kilobyte - refusing to return as text."
            ).toPayload();
        }
        var payload = new LinkedHashMap<String, Object>();
        if (!inline()) {
            payload.put("responseMode", "references");
            payload.put("project", projectRoot.toString());
            payload.put("path", page.path());
            payload.put("title", nullToEmpty(page.title()));
            payload.put("format", page.format().name());
            payload.put("purpose", page.purpose().name());
            payload.put("sizeBytes", size);
            payload.put("contentHash", "sha256:" + ModelIdentity.sha256(new String(bytes, StandardCharsets.UTF_8)));
            payload.put("ref", page.path());
            payload.put("hint", DOC_FETCH_HINT);
            return payload;
        }
        var text = new String(bytes, StandardCharsets.UTF_8);
        payload.put("project", projectRoot.toString());
        payload.put("path", page.path());
        payload.put("title", nullToEmpty(page.title()));
        payload.put("format", page.format().name());
        payload.put("purpose", page.purpose().name());
        payload.put("sizeBytes", size);
        payload.put("content", text);
        return payload;
    }

    /**
     * Load the cached scan or run a fresh one if stale. Shared by every doc tool
     * so the scan-store contract (24h freshness) holds in one place.
     */
    private ProjectContext loadOrScan(Path projectRoot) {
        if (scanStore.isFresh(projectRoot, SCAN_FRESH_WINDOW)) {
            return scanStore.load(projectRoot).orElseGet(() -> scanAndPersist(projectRoot));
        }
        return scanAndPersist(projectRoot);
    }

    /**
     * Map a {@link DocumentationIndex} into the JSON-friendly shape returned by
     * {@code list_documentation} and the docs MCP resource. Pulled out so the
     * tool and the resource render the same payload. When {@code purposeFilter}
     * is non-null, the page list is restricted to entries with that purpose
     * (the unfiltered total is still reported as {@code pageCount}).
     */
    static Map<String, Object> documentationToMap(ProjectContext ctx, Path projectRoot,
                                                  Purpose purposeFilter) {
        var docs = ctx.documentation();
        List<DocumentationPage> all = (docs == null || docs.pages() == null) ? List.of() : docs.pages();
        List<DocumentationPage> shown = purposeFilter == null
            ? all
            : all.stream().filter(p -> p.purpose() == purposeFilter).toList();

        var out = new LinkedHashMap<String, Object>();
        out.put("project", ctx.name());
        out.put("rootPath", projectRoot.toString());
        out.put("pageCount", all.size());
        if (purposeFilter != null) {
            out.put("purposeFilter", purposeFilter.name());
            out.put("filteredCount", shown.size());
        }
        out.put("pages", shown.stream()
            .map(p -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("path", p.path());
                m.put("title", nullToEmpty(p.title()));
                m.put("format", p.format().name());
                m.put("purpose", p.purpose().name());
                return (Map<String, Object>) m;
            })
            .toList());
        return out;
    }

    /** Overload for callers (MCP resource) that never filter. */
    static Map<String, Object> documentationToMap(ProjectContext ctx, Path projectRoot) {
        return documentationToMap(ctx, projectRoot, null);
    }

    /** Tolerant case-insensitive parse of the optional `purpose` MCP argument. */
    private static Purpose parsePurposeFilter(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Purpose.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Scan one doc page's text for {@code needle}. Returns up to
     * {@link #FIND_MATCHES_PER_PAGE} matches; reading is best-effort so an
     * unreadable file just yields zero matches.
     */
    private List<DocMatch> matchesIn(Path file, String needle) {
        try {
            if (!Files.isRegularFile(file)) return List.of();
            long size = Files.size(file);
            if (size > maxFileSizeBytes) return List.of();
            byte[] bytes = Files.readAllBytes(file);
            if (looksBinary(bytes)) return List.of();
            String text = new String(bytes, StandardCharsets.UTF_8);
            var hits = new ArrayList<DocMatch>();
            int lineNo = 0;
            for (String line : text.split("\n", -1)) {
                lineNo++;
                if (line.toLowerCase().contains(needle)) {
                    hits.add(new DocMatch(lineNo, line.trim()));
                    if (hits.size() >= FIND_MATCHES_PER_PAGE) break;
                }
            }
            return hits;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private record DocMatch(int line, String excerpt) {}

    /**
     * Bucket two lists keyed by id into common / divergent / aOnly / bOnly.
     * Content equality is content-hash based, so rules that travel through the
     * same remote standards pack to both projects compare equal regardless of
     * source-file layout.
     */
    private static <T> Map<String, Object> diffById(
        List<T> a, List<T> b,
        Function<T, String> idOf,
        Function<T, String> hashOf,
        Function<T, Map<String, Object>> render
    ) {
        var byIdA = new LinkedHashMap<String, T>();
        for (var item : a) {
            if (idOf.apply(item) != null) byIdA.put(idOf.apply(item), item);
        }
        var byIdB = new LinkedHashMap<String, T>();
        for (var item : b) {
            if (idOf.apply(item) != null) byIdB.put(idOf.apply(item), item);
        }
        var allIds = new TreeSet<String>();
        allIds.addAll(byIdA.keySet());
        allIds.addAll(byIdB.keySet());

        var common = new ArrayList<Map<String, Object>>();
        var divergent = new ArrayList<Map<String, Object>>();
        var aOnly = new ArrayList<Map<String, Object>>();
        var bOnly = new ArrayList<Map<String, Object>>();
        for (var id : allIds) {
            var inA = byIdA.get(id);
            var inB = byIdB.get(id);
            if (inA != null && inB != null) {
                if (hashOf.apply(inA).equals(hashOf.apply(inB))) {
                    common.add(render.apply(inA));
                } else {
                    var pair = new LinkedHashMap<String, Object>();
                    pair.put("id", id);
                    pair.put("a", render.apply(inA));
                    pair.put("b", render.apply(inB));
                    divergent.add(pair);
                }
            } else if (inA != null) {
                aOnly.add(render.apply(inA));
            } else {
                bOnly.add(render.apply(inB));
            }
        }
        return Map.of(
            "common", common,
            "divergent", divergent,
            "aOnly", aOnly,
            "bOnly", bOnly,
            "counts", Map.of(
                "common", common.size(),
                "divergent", divergent.size(),
                "aOnly", aOnly.size(),
                "bOnly", bOnly.size()
            )
        );
    }

    private static String sha256Hex(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String ruleHash(Rule r) {
        return sha256Hex("rule|" + nullToEmpty(r.id())
            + "|" + nullToEmpty(r.title())
            + "|" + nullToEmpty(r.severity())
            + "|" + nullToEmpty(r.description())
            + "|" + nullToEmpty(r.rationale()));
    }

    private static String skillHash(Skill s) {
        var steps = s.steps() == null ? "" : String.join("", s.steps());
        var outs = s.outputExpectations() == null ? "" : String.join("", s.outputExpectations());
        return sha256Hex("skill|" + nullToEmpty(s.id())
            + "|" + nullToEmpty(s.title())
            + "|" + nullToEmpty(s.trigger())
            + "|" + steps
            + "|" + outs
            + "|" + nullToEmpty(s.notes()));
    }

    private static String checklistHash(Checklist c) {
        var sb = new StringBuilder("checklist|").append(nullToEmpty(c.id()))
            .append("|").append(nullToEmpty(c.title())).append("|");
        if (c.items() != null) {
            for (ChecklistItem item : c.items()) {
                sb.append(nullToEmpty(item.id())).append("=")
                  .append(nullToEmpty(item.text())).append("=")
                  .append(item.required()).append("");
            }
        }
        return sha256Hex(sb.toString());
    }

    /**
     * Build the combined + split reference triple for a companion anchor. The
     * combined {@code ref} is human-friendly ({@code path#anchor}); the split
     * {@code path} and {@code anchor} are easier for tools to consume. The anchor
     * is {@link ModelIdentity#slug(String)} of the record's stable id, matching
     * the {@code {#slug}} headings the companions emit.
     */
    private static void putReference(Map<String, Object> target, String companion, String id) {
        var anchor = ModelIdentity.slug(nullToEmpty(id));
        target.put("ref", companion + "#" + anchor);
        target.put("path", companion);
        target.put("anchor", anchor);
    }

    /**
     * First-sentence (or {@value #SUMMARY_MAX_CHARS}-char) summary of a body, so a
     * references payload carries enough to decide what to fetch without re-flooding
     * the body it replaces. Collapses whitespace; empty in, empty out.
     */
    private static String summarize(String body) {
        if (body == null) return "";
        var text = body.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) return "";
        int dot = text.indexOf(". ");
        if (dot > 0 && dot + 1 <= SUMMARY_MAX_CHARS) {
            return text.substring(0, dot + 1);
        }
        if (text.length() <= SUMMARY_MAX_CHARS) return text;
        return text.substring(0, SUMMARY_MAX_CHARS).stripTrailing() + "...";
    }

    /** References-mode projection of a rule: metadata + hash + companion anchor, no body. */
    private static Map<String, Object> ruleRef(Rule r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(r.id()));
        m.put("title", nullToEmpty(r.title()));
        m.put("severity", nullToEmpty(r.severity()));
        m.put("auditable", r.isAuditable());
        m.put("contentHash", ruleHash(r));
        m.put("summary", summarize(r.description()));
        putReference(m, RULES_COMPANION, r.id());
        return m;
    }

    /** References-mode projection of a skill: metadata + hash + companion anchor, no steps. */
    private static Map<String, Object> skillRef(Skill s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(s.id()));
        m.put("title", nullToEmpty(s.title()));
        if (s.trigger() != null && !s.trigger().isBlank()) {
            m.put("triggerSummary", summarize(s.trigger()));
        }
        m.put("contentHash", skillHash(s));
        m.put("summary", summarize(skillSummarySource(s)));
        putReference(m, SKILLS_COMPANION, s.id());
        return m;
    }

    /** References-mode projection of a checklist: metadata + hash + companion anchor, no items. */
    private static Map<String, Object> checklistRef(Checklist c) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(c.id()));
        m.put("title", nullToEmpty(c.title()));
        m.put("itemCount", c.items() == null ? 0 : c.items().size());
        m.put("contentHash", checklistHash(c));
        m.put("summary", summarize(c.title()));
        putReference(m, CHECKLISTS_COMPANION, c.id());
        return m;
    }

    /** Best body source for a skill summary: trigger first, then the first step, then title. */
    private static String skillSummarySource(Skill s) {
        if (s.trigger() != null && !s.trigger().isBlank()) return s.trigger();
        if (s.steps() != null && !s.steps().isEmpty()) return s.steps().get(0);
        return s.title();
    }

    /**
     * Compact reference used in every {@code compare_standards} bucket in
     * references mode: id + hash + summary + companion anchor, no body. The id is
     * carried so {@code aOnly}/{@code bOnly}/{@code common} entries stay
     * identifiable; in the {@code divergent} pair it is redundant with the pair's
     * own id but harmless. This lets {@link #diffById} stay byte-for-byte unchanged
     * - only the render function differs between inline and references modes.
     */
    private static Map<String, Object> ruleCompareRef(Rule r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(r.id()));
        m.put("contentHash", ruleHash(r));
        m.put("summary", summarize(r.description()));
        putReference(m, RULES_COMPANION, r.id());
        return m;
    }

    private static Map<String, Object> skillCompareRef(Skill s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(s.id()));
        m.put("contentHash", skillHash(s));
        m.put("summary", summarize(skillSummarySource(s)));
        putReference(m, SKILLS_COMPANION, s.id());
        return m;
    }

    private static Map<String, Object> checklistCompareRef(Checklist c) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(c.id()));
        m.put("contentHash", checklistHash(c));
        m.put("summary", summarize(c.title()));
        putReference(m, CHECKLISTS_COMPANION, c.id());
        return m;
    }

    private static Map<String, Object> ruleToMap(Rule r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(r.id()));
        m.put("title", nullToEmpty(r.title()));
        m.put("severity", nullToEmpty(r.severity()));
        m.put("description", nullToEmpty(r.description()));
        m.put("rationale", nullToEmpty(r.rationale()));
        return m;
    }

    private static Map<String, Object> skillToMap(Skill s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(s.id()));
        m.put("title", nullToEmpty(s.title()));
        m.put("trigger", nullToEmpty(s.trigger()));
        m.put("steps", s.steps() == null ? List.of() : s.steps());
        return m;
    }

    private static Map<String, Object> checklistToMap(Checklist c) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", nullToEmpty(c.id()));
        m.put("title", nullToEmpty(c.title()));
        m.put("items", c.items() == null ? List.of()
            : c.items().stream().map(i -> Map.of(
                "id", nullToEmpty(i.id()),
                "text", nullToEmpty(i.text()),
                "required", i.required())).toList());
        return m;
    }

    /**
     * Run the support gate before any tool method triggers a scan. Returns the
     * canonical unsupported-project error payload when the project does not
     * meet the Spring Boot Java contract (Maven or Gradle); returns {@code null} when the
     * project is supported and the caller may proceed.
     */
    private Map<String, Object> requireSupported(Path projectRoot) {
        var result = projectSupportDetector.detect(projectRoot);
        if (result.isSupported()) return null;
        return McpError.unsupported("unsupported_project", result.reason()).toPayload();
    }

    /**
     * Canonical envelope for a standards pack whose manifest schemaVersion this
     * Launchpad cannot read. Surfaced by every tool that resolves standards, so a
     * format-incompatible pack returns a machine-readable error rather than a raw
     * stack trace.
     */
    private static Map<String, Object> incompatiblePackSchema(IncompatiblePackSchemaException e) {
        var details = new LinkedHashMap<String, Object>();
        details.put("manifest", e.manifestFile().toString());
        details.put("found", e.foundVersion());
        details.put("supported", e.supportedRange());
        return McpError.unsupported("incompatible_pack_schema", e.getMessage(), e.remediation())
            .withDetails(details)
            .toPayload();
    }

    private ProjectContext scanAndPersist(Path projectRoot) {
        ProjectContext ctx;
        try {
            ctx = scanner.scan(projectRoot.toString(), msg -> { });
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to scan " + projectRoot, e);
        }
        try {
            scanStore.save(projectRoot, ctx);
        } catch (RuntimeException ignored) {
            // Persisting is best-effort - the in-memory ctx is the source of truth for this call.
        }
        return ctx;
    }

    /**
     * Resolve the {@code project} argument (name or path) to a concrete project
     * root. Falls back to the {@code LAUNCHPAD_DEFAULT_PROJECT} env var when no
     * argument is passed - MCP clients (Claude Desktop, Cursor, Cline) all
     * support env vars in their server config, making this the simplest way to
     * pin the server to a default project. Returns either a {@link Resolution.Path}
     * the caller can use directly, or a {@link Resolution.Error} carrying a
     * self-describing JSON payload that lists the known projects so the AI
     * client can ask the user or retry.
     */
    private Resolution resolveProject(String project) {
        var ref = project;
        if (ref == null || ref.isBlank()) {
            ref = defaultProjectFromEnv();
        }
        if (ref == null || ref.isBlank()) {
            return new Resolution.Error(noProjectSpecifiedPayload());
        }
        var resolved = projectRegistry.resolveToPath(ref);
        if (resolved.isEmpty()) {
            return new Resolution.Error(unknownNamePayload(ref));
        }
        return new Resolution.Path(resolved.get());
    }

    /**
     * Look up the {@code LAUNCHPAD_DEFAULT_PROJECT} env var or the
     * {@code launchpad.mcp.default-project} system property, whichever is set.
     * Either gives the MCP client a way to pin a default project so unqualified
     * tool calls don't error out.
     */
    private static String defaultProjectFromEnv() {
        var env = System.getenv("LAUNCHPAD_DEFAULT_PROJECT");
        if (env != null && !env.isBlank()) return env;
        var sys = System.getProperty("launchpad.mcp.default-project");
        return sys == null || sys.isBlank() ? null : sys;
    }

    private Map<String, Object> noProjectSpecifiedPayload() {
        var available = projectRegistry.all().stream()
            .map(LaunchpadMcpTools::registryEntryToMap)
            .toList();
        return McpError.invalidArgument(
            "no_project_specified",
            "No project was specified and LAUNCHPAD_DEFAULT_PROJECT is not set.",
            "Pass a project name from the registry or an absolute path as `project`."
        ).withDetails(Map.of("availableProjects", available)).toPayload();
    }

    private Map<String, Object> unknownNamePayload(String requested) {
        var available = projectRegistry.all().stream()
            .map(LaunchpadMcpTools::registryEntryToMap)
            .toList();
        var details = new LinkedHashMap<String, Object>();
        details.put("requested", requested);
        details.put("availableProjects", available);
        return McpError.notFound(
            "unknown_project",
            "No project named '" + requested + "' in the registry, and the value is not an "
                + "absolute path.",
            "Pass an absolute path or pick one of the available project names."
        ).withDetails(details).toPayload();
    }

    private static Map<String, Object> registryEntryToMap(RegisteredProject p) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("name", p.name());
        entry.put("path", p.path());
        entry.put("stack", nullToEmpty(p.stack()));
        entry.put("addedAt", p.addedAt() == null ? "" : p.addedAt().toString());
        entry.put("lastScannedAt", p.lastScannedAt() == null ? "" : p.lastScannedAt().toString());
        entry.put("workspace", nullToEmpty(p.workspace()));
        entry.put("tags", p.tags());
        entry.put("relatedTo", p.relatedTo());
        return entry;
    }

    /** Sealed result of resolving a project ref. */
    private sealed interface Resolution {
        record Path(java.nio.file.Path value) implements Resolution {}
        record Error(Map<String, Object> payload) implements Resolution {}
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Cheap binary-ness probe: any NUL byte in the first kilobyte means we
     * treat the file as binary and refuse to return it as text. This catches
     * jars, class files, images, and compiled artifacts without invoking
     * libmagic or guessing by extension.
     */
    private static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 1024);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    /** Read raw bytes if a file exists; used by the audit resource. */
    static String readIfExists(Path path) {
        if (path == null) return "";
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
