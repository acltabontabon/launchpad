package com.acltabontabon.launchpad.mcp;

import com.acltabontabon.launchpad.audit.AuditService;
import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.config.RegisteredProject;
import com.acltabontabon.launchpad.scanner.DocumentationIndex;
import com.acltabontabon.launchpad.scanner.DocumentationPage;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.ProjectScanner;
import com.acltabontabon.launchpad.scanner.ScanStore;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
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

    private static final Duration SCAN_FRESH_WINDOW = Duration.ofHours(24);

    // Mirrors ProjectScanner.SKIP_DIRS - file-tree walks here must not descend
    // into build outputs / VCS dirs either, both for performance and to avoid
    // surfacing files no caller asked about.
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "build", ".idea", ".vscode",
        "__pycache__", ".gradle", "dist", "out", ".next", ".nuxt", "vendor",
        ".venv", "venv", ".tox", ".pytest_cache", ".mypy_cache", ".cache",
        ".terraform"
    );

    // Hard cap on list_files result size. Big enough for "show me every Java
    // file" on a realistic project; small enough that a wildcard glob on the
    // home directory won't blow up the MCP response.
    private static final int LIST_FILES_LIMIT = 500;

    // Per-page and per-project caps on find_documentation results. Keep MCP
    // responses bounded even when a query happens to hit every paragraph.
    private static final int FIND_MATCHES_PER_PAGE = 5;
    private static final int FIND_HITS_PER_PROJECT = 50;

    private final ProjectScanner scanner;
    private final ScanStore scanStore;
    private final AuditService auditService;
    private final StandardsLoader standardsLoader;
    private final ProjectRegistry projectRegistry;
    private final long maxFileSizeBytes;

    public LaunchpadMcpTools(ProjectScanner scanner,
                             ScanStore scanStore,
                             AuditService auditService,
                             StandardsLoader standardsLoader,
                             ProjectRegistry projectRegistry,
                             @Value("${launchpad.scan.max-file-size-kb:512}") long maxFileSizeKb) {
        this.scanner = scanner;
        this.scanStore = scanStore;
        this.auditService = auditService;
        this.standardsLoader = standardsLoader;
        this.projectRegistry = projectRegistry;
        this.maxFileSizeBytes = maxFileSizeKb * 1024L;
    }

    @McpTool(
        name = "list_projects",
        description = "List every project the user has ever used Launchpad on (from the local registry "
            + "at ~/.launchpad/projects.json). Each entry carries a short name, absolute path, stack "
            + "label, timestamps, and any relationship metadata (tags / workspace / relatedTo) read "
            + "from the project's `.launchpad/project.yml`. Pass `workspace` to narrow the list to "
            + "one logical group - useful when the user has many projects but only a related cluster "
            + "matters for the current task. Call this first when the user asks about \"my projects\" "
            + "or doesn't remember a path - then pass the chosen name to scan_project, get_standards, "
            + "or get_audit_findings via the `project` argument."
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
            + "Use this before calling get_standards or get_audit_findings on the same project."
    )
    public Map<String, Object> scanProject(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
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
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
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
        name = "get_file",
        description = "Read a single file from a registered project's source tree. The `path` is "
            + "resolved against the project root with traversal protection - any attempt to escape "
            + "the project (e.g. ../../etc/passwd) is rejected. Refuses files larger than "
            + "launchpad.scan.max-file-size-kb (default 512 KB) and files that look binary. "
            + "Returns the content as UTF-8 text along with size and line count. The `project` "
            + "argument accepts either a short name from list_projects or an absolute path."
    )
    public Map<String, Object> getFile(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project,
        @McpArg(name = "path", description = "Project-relative file path, e.g. src/Main.java",
            required = true)
        String path
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();

        if (path == null || path.isBlank()) {
            return errorPayload("missing_path", "Pass a project-relative file path as `path`.");
        }
        Path target;
        try {
            target = projectRoot.resolve(path).normalize();
        } catch (RuntimeException e) {
            return errorPayload("invalid_path", "Path could not be resolved: " + e.getMessage());
        }
        if (!target.startsWith(projectRoot)) {
            return errorPayload("path_escapes_project",
                "The resolved path is outside the project root - refusing to read.");
        }
        if (!Files.isRegularFile(target)) {
            return errorPayload("not_a_file", "No regular file at " + path + " under the project root.");
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            return errorPayload("stat_failed", "Could not stat " + path + ": " + e.getMessage());
        }
        if (size > maxFileSizeBytes) {
            return errorPayload("file_too_large",
                "File is " + size + " bytes; limit is " + maxFileSizeBytes
                    + " (set by launchpad.scan.max-file-size-kb).");
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(target);
        } catch (IOException e) {
            return errorPayload("read_failed", "Could not read " + path + ": " + e.getMessage());
        }
        if (looksBinary(bytes)) {
            return errorPayload("file_appears_binary",
                "File contains NUL bytes in its first kilobyte - refusing to return as text.");
        }
        var text = new String(bytes, StandardCharsets.UTF_8);
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return Map.of(
            "project", projectRoot.toString(),
            "path", projectRoot.relativize(target).toString(),
            "sizeBytes", size,
            "lineCount", lines,
            "content", text
        );
    }

    @McpTool(
        name = "list_files",
        description = "List files in a registered project matching a glob. Glob syntax follows "
            + "Java NIO PathMatcher: `**` matches any depth, `*` matches one path segment, "
            + "e.g. `**/*.java` or `src/main/**/*Repository.java`. Walks the project tree but "
            + "skips well-known build outputs and VCS dirs (target, build, node_modules, .git, "
            + "etc.). Results are project-relative paths, sorted, capped at " + LIST_FILES_LIMIT
            + " entries. The `project` argument accepts either a short name from list_projects "
            + "or an absolute path."
    )
    public Map<String, Object> listFiles(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project,
        @McpArg(name = "glob", description = "Glob pattern (Java NIO PathMatcher), e.g. **/*.java",
            required = true)
        String glob
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();

        if (glob == null || glob.isBlank()) {
            return errorPayload("missing_glob",
                "Pass a glob pattern as `glob`, e.g. **/*.java or src/main/**/*.kt.");
        }

        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (IllegalArgumentException e) {
            return errorPayload("invalid_glob", "Pattern is not a valid glob: " + e.getMessage());
        }

        var matches = new ArrayList<String>();
        boolean[] truncated = { false };
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    var name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(projectRoot) && SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    var rel = projectRoot.relativize(file);
                    if (matcher.matches(rel)) {
                        if (matches.size() >= LIST_FILES_LIMIT) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        matches.add(rel.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return errorPayload("walk_failed", "Could not walk project tree: " + e.getMessage());
        }

        Collections.sort(matches);
        return Map.of(
            "project", projectRoot.toString(),
            "glob", glob,
            "matchCount", matches.size(),
            "truncated", truncated[0],
            "limit", LIST_FILES_LIMIT,
            "files", matches
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
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
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

    @McpTool(
        name = "compare_standards",
        description = "Diff the standards (rules, skills, checklists) that apply to two registered "
            + "projects. Returns four buckets per kind: `common` (same id, same content on both "
            + "sides), `divergent` (same id, different content - both sides included for inspection), "
            + "`aOnly`, `bOnly`. Useful for spotting whether a recommendation that holds for project "
            + "A also holds for project B - especially after one project has overridden the shared "
            + "remote standards pack."
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

        var rulesDiff = diffById(
            standardsLoader.loadRules(rootA), standardsLoader.loadRules(rootB),
            Rule::id, LaunchpadMcpTools::ruleHash, LaunchpadMcpTools::ruleToMap);
        var skillsDiff = diffById(
            standardsLoader.loadSkills(rootA), standardsLoader.loadSkills(rootB),
            Skill::id, LaunchpadMcpTools::skillHash, LaunchpadMcpTools::skillToMap);
        var checklistsDiff = diffById(
            standardsLoader.loadChecklists(rootA), standardsLoader.loadChecklists(rootB),
            Checklist::id, LaunchpadMcpTools::checklistHash, LaunchpadMcpTools::checklistToMap);

        return Map.of(
            "projectA", rootA.toString(),
            "projectB", rootB.toString(),
            "rules", rulesDiff,
            "skills", skillsDiff,
            "checklists", checklistsDiff
        );
    }

    @McpTool(
        name = "list_documentation",
        description = "List the documentation pages discovered for a registered project. Returns the "
            + "detected format (MKDOCS / ANTORA / PLAIN / NONE), site name (when available), docs "
            + "directory, and the ordered page list - each page carries its project-relative path, "
            + "extracted title, and page format (MARKDOWN / ASCIIDOC / RST). Markdown and AsciiDoc "
            + "are equally supported. Reads from the cached scan and falls back to running a fresh "
            + "scan when stale. The `project` argument accepts either a short name from the registry "
            + "(see list_projects) or an absolute path."
    )
    public Map<String, Object> listDocumentation(
        @McpArg(name = "project", description = "Project name (from list_projects) or absolute path; "
            + "falls back to LAUNCHPAD_DEFAULT_PROJECT", required = false)
        String project
    ) {
        var resolved = resolveProject(project);
        if (resolved instanceof Resolution.Error err) return err.payload();
        var projectRoot = ((Resolution.Path) resolved).value();
        var ctx = loadOrScan(projectRoot);
        return documentationToMap(ctx, projectRoot);
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
            + "the caller decide whether to refine the query."
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
            return errorPayload("missing_query", "Pass a non-empty `query` substring to search for.");
        }
        String needle = query.toLowerCase();

        List<Path> roots = new ArrayList<>();
        if (project != null && !project.isBlank()) {
            var resolved = resolveProject(project);
            if (resolved instanceof Resolution.Error err) return err.payload();
            roots.add(((Resolution.Path) resolved).value());
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
        description = "Read a single documentation page (Markdown, AsciiDoc, or RST) from a registered "
            + "project. The `path` must match a page surfaced by list_documentation - reads outside "
            + "the detected docs set are rejected, even when the file exists, so the AI cannot use "
            + "this tool as a generic file reader. Inherits the binary, size, and traversal guards "
            + "from the file-read path. The `project` argument accepts either a short name or an "
            + "absolute path."
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

        if (path == null || path.isBlank()) {
            return errorPayload("missing_path", "Pass a project-relative doc-page path as `path`.");
        }

        var ctx = loadOrScan(projectRoot);
        var docs = ctx.documentation();
        if (docs == null || docs.isEmpty()) {
            return errorPayload("no_documentation",
                "No documentation detected for this project. Call list_documentation first.");
        }
        DocumentationPage page = null;
        for (var p : docs.pages()) {
            if (p.path().equals(path)) { page = p; break; }
        }
        if (page == null) {
            return errorPayload("path_not_in_docs_index",
                "The path is not part of the detected docs set for this project. Use "
                    + "list_documentation to see allowed paths.");
        }

        Path target;
        try {
            target = projectRoot.resolve(path).normalize();
        } catch (RuntimeException e) {
            return errorPayload("invalid_path", "Path could not be resolved: " + e.getMessage());
        }
        if (!target.startsWith(projectRoot)) {
            return errorPayload("path_escapes_project",
                "The resolved path is outside the project root - refusing to read.");
        }
        if (!Files.isRegularFile(target)) {
            return errorPayload("not_a_file", "No regular file at " + path + " under the project root.");
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            return errorPayload("stat_failed", "Could not stat " + path + ": " + e.getMessage());
        }
        if (size > maxFileSizeBytes) {
            return errorPayload("file_too_large",
                "Doc page is " + size + " bytes; limit is " + maxFileSizeBytes
                    + " (set by launchpad.scan.max-file-size-kb).");
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(target);
        } catch (IOException e) {
            return errorPayload("read_failed", "Could not read " + path + ": " + e.getMessage());
        }
        if (looksBinary(bytes)) {
            return errorPayload("file_appears_binary",
                "File contains NUL bytes in its first kilobyte - refusing to return as text.");
        }
        var text = new String(bytes, StandardCharsets.UTF_8);
        return Map.of(
            "project", projectRoot.toString(),
            "path", page.path(),
            "title", nullToEmpty(page.title()),
            "format", page.format().name(),
            "sizeBytes", size,
            "content", text
        );
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
     * tool and the resource render the same payload.
     */
    static Map<String, Object> documentationToMap(ProjectContext ctx, Path projectRoot) {
        var docs = ctx.documentation();
        var out = new LinkedHashMap<String, Object>();
        out.put("project", ctx.name());
        out.put("rootPath", projectRoot.toString());
        out.put("format", docs == null ? "NONE" : docs.format().name());
        out.put("siteName", docs == null ? "" : nullToEmpty(docs.siteName()));
        out.put("docsDir", docs == null ? "" : nullToEmpty(docs.docsDir()));
        List<Map<String, Object>> pages = (docs == null || docs.pages() == null)
            ? List.of()
            : docs.pages().stream()
                .map(p -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("path", p.path());
                    m.put("title", nullToEmpty(p.title()));
                    m.put("format", p.format().name());
                    return (Map<String, Object>) m;
                })
                .toList();
        out.put("pageCount", pages.size());
        out.put("pages", pages);
        return out;
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

    private static Map<String, Object> errorPayload(String code, String message) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("error", code);
        payload.put("message", message);
        return payload;
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
