package com.acltabontabon.launchpad.mcp;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.nio.file.Path;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Exposes Launchpad's audit report as an MCP resource so SARIF-aware clients
 * (VS Code SARIF Viewer, IntelliJ Qodana, GitHub code-scanning ingestors) can
 * pull the document directly instead of running the tool and parsing JSON
 * out of a tool result.
 * <p>
 * Resource URI is parameterised by project path:
 * {@code launchpad://audit/{projectPath}}. The path is URL-decoded; clients
 * typically embed the absolute project root.
 */
@Component
@ConditionalOnProperty(name = "launchpad.mode", havingValue = "mcp")
public class LaunchpadMcpResources {

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
        var projectRoot = Path.of(projectPath).toAbsolutePath();
        var sarifFile = projectRoot.resolve(".launchpad").resolve("audit.sarif.json");
        var content = LaunchpadMcpTools.readIfExists(sarifFile);
        var body = content.isEmpty()
            ? "{ \"version\": \"2.1.0\", \"runs\": [], \"note\": \"No audit has run for this project yet. Call get_audit_findings first.\" }"
            : content;
        return new ReadResourceResult(List.of(
            new TextResourceContents(request.uri(), "application/sarif+json", body)
        ));
    }
}
