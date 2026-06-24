package com.acltabontabon.launchpad.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.model.Confidence;
import com.acltabontabon.launchpad.model.ProjectIdentity;
import com.acltabontabon.launchpad.model.VirtualProjectContext;
import com.acltabontabon.launchpad.model.VirtualProjectContextStore;
import com.acltabontabon.launchpad.model.Workflow;
import com.acltabontabon.launchpad.model.WorkflowType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused coverage for the get_workflows MCP tool. Only the project registry and
 * the model store participate, so the other collaborators are left null.
 */
class LaunchpadMcpToolsWorkflowsTest {

    // Absolute-path project refs resolve without consulting the registry's
    // contents, so the default registry (read-only) is enough for these tests.
    private LaunchpadMcpTools toolsFor(VirtualProjectContextStore store) {
        return new LaunchpadMcpTools(
            null, null, null, null,
            new ProjectRegistry(),
            null,
            store,
            null,
            512,
            null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsPersistedWorkflowsForProjectPath(@TempDir Path tmp) {
        var projectRoot = tmp.resolve("proj");
        var store = new VirtualProjectContextStore();
        var workflow = new Workflow(
            "loancontroller", "Loan", "", WorkflowType.INBOUND_API,
            "POST /loans", List.of("POST /loans"),
            List.of("LoanService"), List.of("CreditBureauClient"), List.of("LoanRepository"),
            Confidence.DETERMINISTIC, List.of());
        var model = new VirtualProjectContext(
            new ProjectIdentity("proj", projectRoot.toString(), "Spring Boot", "", "", "h"),
            null, List.of(), List.of(workflow), null, null, null, List.of());
        store.save(projectRoot, model);

        var tools = toolsFor(store);
        var out = tools.getWorkflows(projectRoot.toString());

        assertThat(out).containsEntry("workflowCount", 1);
        var workflows = (List<Map<String, Object>>) out.get("workflows");
        assertThat(workflows).hasSize(1);
        var w = workflows.get(0);
        assertThat(w).containsEntry("name", "Loan");
        assertThat(w).containsEntry("type", "INBOUND_API");
        assertThat(w.get("touchedSystems")).isEqualTo(List.of("LoanService"));
        assertThat(w.get("dataEffects")).isEqualTo(List.of("LoanRepository"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsErrorWhenNoModelExists(@TempDir Path tmp) {
        var tools = toolsFor(new VirtualProjectContextStore());
        var out = tools.getWorkflows(tmp.resolve("unscanned").toString());
        var err = (Map<String, Object>) out.get("error");
        assertThat(err).containsEntry("code", "no_project_model");
        assertThat(err).containsEntry("type", "NOT_FOUND");
    }
}
