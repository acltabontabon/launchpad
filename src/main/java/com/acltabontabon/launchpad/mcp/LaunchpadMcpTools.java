package com.acltabontabon.launchpad.mcp;

import com.acltabontabon.launchpad.audit.AuditService;
import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.config.RegisteredProject;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.ProjectScanner;
import com.acltabontabon.launchpad.scanner.ScanStore;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposing Launchpad's project intelligence to any MCP client
 * (Claude Code, Cursor, Cline, Continue, Zed, ...). Deliberately scoped to
 * what is <em>unique</em> to Launchpad - scan results, applicable standards,
 * audit findings - so we don't duplicate the official
 * {@code mcp-server-filesystem} or {@code mcp-server-git}.
 * <p>
 * All three tools read from {@code <project>/.launchpad/} when fresh artifacts
 * exist (24h freshness window for the scan) and fall back to running the
 * underlying service when they don't. This means the MCP server works
 * standalone - a developer who never opens the TUI still gets value.
 */
@Component
@ConditionalOnProperty(name = "launchpad.mode", havingValue = "mcp")
public class LaunchpadMcpTools {

    private static final Duration SCAN_FRESH_WINDOW = Duration.ofHours(24);

    private final ProjectScanner scanner;
    private final ScanStore scanStore;
    private final AuditService auditService;
    private final StandardsLoader standardsLoader;
    private final ProjectRegistry projectRegistry;

    public LaunchpadMcpTools(ProjectScanner scanner,
                             ScanStore scanStore,
                             AuditService auditService,
                             StandardsLoader standardsLoader,
                             ProjectRegistry projectRegistry) {
        this.scanner = scanner;
        this.scanStore = scanStore;
        this.auditService = auditService;
        this.standardsLoader = standardsLoader;
        this.projectRegistry = projectRegistry;
    }

    @McpTool(
        name = "list_projects",
        description = "List every project the user has ever used Launchpad on (from the local registry "
            + "at ~/.launchpad/projects.json). Each entry carries a short name, absolute path, stack "
            + "label, and timestamps. Call this first when the user asks about \"my projects\" or "
            + "doesn't remember a path - then pass the chosen name to scan_project, get_standards, "
            + "or get_audit_findings via the `project` argument."
    )
    public Map<String, Object> listProjects() {
        var entries = projectRegistry.all().stream()
            .map(LaunchpadMcpTools::registryEntryToMap)
            .toList();
        return Map.of("projects", entries, "count", entries.size());
    }

    @McpTool(
        name = "scan_project",
        description = "Scan a project directory and return a structured summary of its stack, framework, "
            + "dependencies, package structure, and source file list. Returns a cached result when one "
            + "exists and is younger than 24 hours; otherwise runs a fresh scan. The `project` argument "
            + "accepts either a short name from the registry (see list_projects) or an absolute path. "
            + "Use this before calling get_standards or get_audit_findings on the same project."
    )
    public Map<String, Object> scanProject(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path",
            required = false)
        String project
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
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
        description = "Return the engineering rules, skills, and checklists from the standards pack that "
            + "applies to a project. These are the same standards Launchpad embeds in CLAUDE.md / "
            + ".cursorrules, but as structured data so MCP clients can reason about them "
            + "programmatically. The `project` argument accepts either a short name from the registry "
            + "(see list_projects) or an absolute path."
    )
    public Map<String, Object> getStandards(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path",
            required = false)
        String project
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var rules = standardsLoader.loadRules(projectRoot).stream()
            .map(r -> Map.of(
                "id", nullToEmpty(r.id()),
                "title", nullToEmpty(r.title()),
                "severity", nullToEmpty(r.severity()),
                "description", nullToEmpty(r.description()),
                "rationale", nullToEmpty(r.rationale()),
                "auditable", r.isAuditable()))
            .toList();
        var skills = standardsLoader.loadSkills(projectRoot).stream()
            .map(s -> Map.of(
                "id", nullToEmpty(s.id()),
                "title", nullToEmpty(s.title()),
                "trigger", nullToEmpty(s.trigger()),
                "steps", s.steps() == null ? List.of() : s.steps()))
            .toList();
        var checklists = standardsLoader.loadChecklists(projectRoot).stream()
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

    @McpTool(
        name = "get_audit_findings",
        description = "Return standards audit findings for a project (rule violations detected by "
            + "Launchpad's local-AI + pattern audit engine). Reads .launchpad/audit.sarif.json when "
            + "present and runs a fresh audit otherwise. Each finding carries ruleId, severity, "
            + "filePath, line, and message - clients can filter the returned list by severity "
            + "(never|must|should|avoid) or ruleId. The `project` argument accepts either a short name "
            + "from the registry (see list_projects) or an absolute path."
    )
    public Map<String, Object> getAuditFindings(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path",
            required = false)
        String project
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var ctx = scanStore.load(projectRoot).orElseGet(() -> scanAndPersist(projectRoot));
        var result = auditService.run(ctx, projectRoot);
        var findings = result.findings().stream()
            .map(f -> Map.of(
                "ruleId", nullToEmpty(f.ruleId()),
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
     * root. Returns either a {@link Resolution.Path} the caller can use directly,
     * or a {@link Resolution.Error} carrying a self-describing JSON payload that
     * lists the known projects so the AI client can ask the user or retry.
     */
    private Resolution resolveProject(String project) {
        if (project == null || project.isBlank()) {
            return new Resolution.Error(noProjectSpecifiedPayload());
        }
        var resolved = projectRegistry.resolveToPath(project);
        if (resolved.isEmpty()) {
            return new Resolution.Error(unknownNamePayload(project));
        }
        return new Resolution.Path(resolved.get());
    }

    private Map<String, Object> noProjectSpecifiedPayload() {
        var available = projectRegistry.all().stream()
            .map(LaunchpadMcpTools::registryEntryToMap)
            .toList();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("error", "no_project_specified");
        payload.put("message", "Pass either a project name from the registry or an absolute path "
            + "as the `project` argument.");
        payload.put("availableProjects", available);
        return payload;
    }

    private Map<String, Object> unknownNamePayload(String requested) {
        var available = projectRegistry.all().stream()
            .map(LaunchpadMcpTools::registryEntryToMap)
            .toList();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("error", "unknown_project");
        payload.put("message", "No project named '" + requested + "' in the registry, and the value "
            + "is not an absolute path. Pass an absolute path or pick one of the available names.");
        payload.put("requested", requested);
        payload.put("availableProjects", available);
        return payload;
    }

    private static Map<String, Object> registryEntryToMap(RegisteredProject p) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("name", p.name());
        entry.put("path", p.path());
        entry.put("stack", nullToEmpty(p.stack()));
        entry.put("addedAt", p.addedAt() == null ? "" : p.addedAt().toString());
        entry.put("lastScannedAt", p.lastScannedAt() == null ? "" : p.lastScannedAt().toString());
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
