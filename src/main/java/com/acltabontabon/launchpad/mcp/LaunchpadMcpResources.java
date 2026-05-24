package com.acltabontabon.launchpad.mcp;

import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.scanner.ScanStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/**
 * Exposes Launchpad's project intelligence as MCP resources, so SARIF-aware
 * clients (VS Code SARIF Viewer, IntelliJ Qodana) and tree-browsing clients
 * (Claude Desktop) can read the documents directly instead of running tools.
 * <p>
 * URI templates:
 * <ul>
 *   <li>{@code launchpad://audit/{projectPath}} - SARIF audit findings.</li>
 *   <li>{@code launchpad://projects} - registry listing as JSON.</li>
 *   <li>{@code launchpad://scan/{name}} - per-project scan snapshot as JSON.</li>
 *   <li>{@code launchpad://docs/{name}} - detected documentation index for a project.</li>
 * </ul>
 */
@Component
public class LaunchpadMcpResources {

    private final ProjectRegistry projectRegistry;
    private final ScanStore scanStore;
    private final ObjectMapper json;

    public LaunchpadMcpResources(ProjectRegistry projectRegistry, ScanStore scanStore) {
        this.projectRegistry = projectRegistry;
        this.scanStore = scanStore;
        this.json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @McpResource(
        uri = "launchpad://audit/{projectPath}",
        name = "audit",
        title = "Standards Audit (SARIF)",
        description = "The latest standards audit findings for a project, as SARIF 2.1.0 JSON. "
            + "Read launchpad://audit/<abs-project-path>. The document is the same one written to "
            + "<project>/.launchpad/audit.sarif.json by the audit engine.",
        mimeType = "application/sarif+json"
    )
    public ReadResourceResult readAudit(ReadResourceRequest request, String projectPath) {
        var decoded = URLDecoder.decode(projectPath, StandardCharsets.UTF_8);
        var projectRoot = Path.of(decoded).toAbsolutePath();
        var sarifFile = projectRoot.resolve(".launchpad").resolve("audit.sarif.json");
        var content = LaunchpadMcpTools.readIfExists(sarifFile);
        var body = content.isEmpty()
            ? "{ \"version\": \"2.1.0\", \"runs\": [], \"note\": \"No audit has run for this project yet. Call get_audit_findings first.\" }"
            : content;
        return new ReadResourceResult(List.of(
            new TextResourceContents(request.uri(), "application/sarif+json", body)
        ));
    }

    @McpResource(
        uri = "launchpad://projects",
        name = "projects",
        title = "Registered Projects",
        description = "The full list of projects the user has used Launchpad on, sourced from "
            + "~/.launchpad/projects.json. Each entry carries name, absolute path, stack label, "
            + "and timestamps. Equivalent payload to the list_projects tool, but exposed as a "
            + "resource for clients that browse the resource tree before running tools.",
        mimeType = "application/json"
    )
    public ReadResourceResult readProjects(ReadResourceRequest request) {
        var entries = projectRegistry.all().stream()
            .map(p -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("name", p.name());
                m.put("path", p.path());
                m.put("stack", p.stack() == null ? "" : p.stack());
                m.put("addedAt", p.addedAt() == null ? "" : p.addedAt().toString());
                m.put("lastScannedAt", p.lastScannedAt() == null ? "" : p.lastScannedAt().toString());
                return m;
            })
            .toList();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("projects", entries);
        payload.put("count", entries.size());
        return new ReadResourceResult(List.of(
            new TextResourceContents(request.uri(), "application/json", writeJson(payload))
        ));
    }

    @McpResource(
        uri = "launchpad://scan/{name}",
        name = "scan",
        title = "Project Scan Snapshot",
        description = "The cached scan summary for a registered project: stack, framework, "
            + "dependencies, package structure, and source file list. Reads from "
            + "<project>/.launchpad/scan.json. The `name` segment is the short project name "
            + "from launchpad://projects. Returns an empty placeholder when the project is "
            + "unknown or has never been scanned.",
        mimeType = "application/json"
    )
    public ReadResourceResult readScan(ReadResourceRequest request, String name) {
        var decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
        var resolved = projectRegistry.findByName(decoded);
        if (resolved.isEmpty()) {
            return new ReadResourceResult(List.of(new TextResourceContents(request.uri(),
                "application/json",
                "{ \"error\": \"unknown_project\", \"requested\": \"" + decoded
                    + "\", \"note\": \"Use launchpad://projects to discover registered names.\" }")));
        }
        var projectRoot = Path.of(resolved.get().path());
        var ctx = scanStore.load(projectRoot);
        if (ctx.isEmpty()) {
            var miss = new LinkedHashMap<String, Object>();
            miss.put("name", resolved.get().name());
            miss.put("path", resolved.get().path());
            miss.put("scanned", false);
            miss.put("note", "No scan cache yet. Call scan_project with this name to populate it.");
            miss.put("checkedAt", Instant.now().toString());
            return new ReadResourceResult(List.of(new TextResourceContents(request.uri(),
                "application/json", writeJson(miss))));
        }
        return new ReadResourceResult(List.of(new TextResourceContents(request.uri(),
            "application/json", writeJson(ctx.get()))));
    }

    @McpResource(
        uri = "launchpad://docs/{name}",
        name = "docs",
        title = "Project Documentation Index",
        description = "The Markdown and AsciiDoc pages detected for a registered project as a "
            + "flat ordered list. Each page entry carries its project-relative path, extracted "
            + "title, renderer format (MARKDOWN / ASCIIDOC), and a coarse purpose tag (OVERVIEW "
            + "/ SETUP / ARCHITECTURE / API / OPERATIONS / CONTRIBUTION / CHANGELOG / UNKNOWN). "
            + "The `name` segment is the short project name from launchpad://projects. Returns "
            + "an empty placeholder when the project is unknown or has never been scanned. "
            + "Mirrors the list_documentation tool for clients that browse the resource tree.",
        mimeType = "application/json"
    )
    public ReadResourceResult readDocs(ReadResourceRequest request, String name) {
        var decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
        var resolved = projectRegistry.findByName(decoded);
        if (resolved.isEmpty()) {
            return new ReadResourceResult(List.of(new TextResourceContents(request.uri(),
                "application/json",
                "{ \"error\": \"unknown_project\", \"requested\": \"" + decoded
                    + "\", \"note\": \"Use launchpad://projects to discover registered names.\" }")));
        }
        var projectRoot = Path.of(resolved.get().path());
        var ctx = scanStore.load(projectRoot);
        if (ctx.isEmpty()) {
            var miss = new LinkedHashMap<String, Object>();
            miss.put("name", resolved.get().name());
            miss.put("path", resolved.get().path());
            miss.put("scanned", false);
            miss.put("note", "No scan cache yet. Call scan_project with this name to populate it.");
            miss.put("checkedAt", Instant.now().toString());
            return new ReadResourceResult(List.of(new TextResourceContents(request.uri(),
                "application/json", writeJson(miss))));
        }
        var payload = LaunchpadMcpTools.documentationToMap(ctx.get(), projectRoot);
        return new ReadResourceResult(List.of(new TextResourceContents(request.uri(),
            "application/json", writeJson(payload))));
    }

    private String writeJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{ \"error\": \"serialization_failed\", \"message\": \""
                + e.getMessage().replace("\"", "\\\"") + "\" }";
        }
    }
}
