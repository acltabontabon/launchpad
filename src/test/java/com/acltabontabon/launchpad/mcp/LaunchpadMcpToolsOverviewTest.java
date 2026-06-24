package com.acltabontabon.launchpad.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.model.Architecture;
import com.acltabontabon.launchpad.model.Confidence;
import com.acltabontabon.launchpad.model.InferredStandard;
import com.acltabontabon.launchpad.model.Operations;
import com.acltabontabon.launchpad.model.ProjectIdentity;
import com.acltabontabon.launchpad.model.Risk;
import com.acltabontabon.launchpad.model.RiskSeverity;
import com.acltabontabon.launchpad.model.StandardsProfile;
import com.acltabontabon.launchpad.model.SystemComponent;
import com.acltabontabon.launchpad.model.VirtualProjectContext;
import com.acltabontabon.launchpad.model.VirtualProjectContextStore;
import com.acltabontabon.launchpad.model.Workflow;
import com.acltabontabon.launchpad.model.WorkflowType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchpadMcpToolsOverviewTest {

    private LaunchpadMcpTools toolsFor(VirtualProjectContextStore store) {
        return new LaunchpadMcpTools(null, null, null, null, new ProjectRegistry(), null, store, null, null, 512, null);
    }

    private VirtualProjectContext richModel(Path root) {
        var identity = new ProjectIdentity("demo", root.toString(), "Spring Boot / Java / Maven",
            "2026-05-31T00:00:00Z", "", "h");
        var arch = new Architecture("layered", List.of("controller", "service"), List.of(),
            Map.of(), "", List.of(), Confidence.DETERMINISTIC, List.of());
        var system = new SystemComponent("web", "com/acme/web", "Web tier", List.of(),
            List.of("com/acme/web"), List.of(), Confidence.DETERMINISTIC, List.of());
        var workflow = new Workflow("loan", "Loan Decision", "", WorkflowType.INBOUND_API,
            "POST /loans", List.of("POST /loans"), List.of("LoanService"), List.of(), List.of(),
            Confidence.DETERMINISTIC, List.of());
        var risk = new Risk("layering-drift", "layering", RiskSeverity.MEDIUM,
            "Controller reaches data store directly.", List.of(), List.of("web"),
            "Add a service.", Confidence.INFERRED);
        var guardrail = new InferredStandard("controller-service-delegation",
            "Controllers delegate to a service layer", 1.0, List.of(),
            "[should] Controllers must delegate to a service layer.", Confidence.LLM_SUGGESTED);
        var ops = new Operations(List.of(), List.of(), List.of(), List.of("GET /actuator/health"),
            List.of("./mvnw test"), List.of(), List.of());
        return new VirtualProjectContext(identity, arch, List.of(system), List.of(workflow),
            new StandardsProfile(List.of(), List.of(), List.of(guardrail)), ops, null, List.of(risk));
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewAggregatesTheFiveMinuteBrief(@TempDir Path tmp) {
        var root = tmp.resolve("proj");
        var store = new VirtualProjectContextStore();
        store.save(root, richModel(root));

        var out = toolsFor(store).getProjectOverview(root.toString());

        assertThat(out).containsEntry("stack", "Spring Boot / Java / Maven");
        assertThat((Map<String, Object>) out.get("architecture")).containsEntry("style", "layered");
        assertThat((List<String>) out.get("systems")).contains("com/acme/web");
        assertThat((Map<String, Object>) out.get("workflowsByType"))
            .containsEntry("INBOUND_API", List.of("Loan Decision"));
        assertThat((List<String>) out.get("buildCommands")).contains("./mvnw test");
        assertThat((List<String>) out.get("risks")).anyMatch(r -> r.contains("layering"));
        assertThat((List<String>) out.get("suggestedGuardrails")).isNotEmpty();
        assertThat((Map<String, Object>) out.get("counts")).containsEntry("workflows", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSystemsAndGetRisksReturnModelEntries(@TempDir Path tmp) {
        var root = tmp.resolve("proj");
        var store = new VirtualProjectContextStore();
        store.save(root, richModel(root));
        var tools = toolsFor(store);

        var systems = tools.getSystems(root.toString());
        assertThat(systems).containsEntry("systemCount", 1);
        assertThat((List<Map<String, Object>>) systems.get("systems"))
            .anyMatch(m -> "Web tier".equals(m.get("responsibility")));

        var risks = tools.getRisks(root.toString());
        assertThat(risks).containsEntry("riskCount", 1);
        assertThat((List<Map<String, Object>>) risks.get("risks"))
            .anyMatch(m -> "layering".equals(m.get("category")));
    }

    @Test
    void reportErrorWhenNoModel(@TempDir Path tmp) {
        var tools = toolsFor(new VirtualProjectContextStore());
        assertThat(errorCode(tools.getProjectOverview(tmp.resolve("none").toString())))
            .isEqualTo("no_project_model");
        assertThat(errorCode(tools.getSystems(tmp.resolve("none").toString())))
            .isEqualTo("no_project_model");
        assertThat(errorCode(tools.getRisks(tmp.resolve("none").toString())))
            .isEqualTo("no_project_model");
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(Map<String, Object> out) {
        var err = (Map<String, Object>) out.get("error");
        return err == null ? null : (String) err.get("code");
    }
}
