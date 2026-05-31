package com.acltabontabon.launchpad.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.model.Confidence;
import com.acltabontabon.launchpad.model.Workflow;
import com.acltabontabon.launchpad.model.WorkflowType;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowDiscovererTest {

    private final WorkflowDiscoverer discoverer = new WorkflowDiscoverer();

    private static ProjectContext scanWith(List<Endpoint> endpoints) {
        return new ProjectContext(
            "demo",
            "/repos/demo",
            new StackProfile("Java", "Maven", "Spring Boot", List.of()),
            List.of(),
            List.of(),
            Map.of(),
            List.of(),
            Map.of(),
            List.of(),
            null,
            com.acltabontabon.launchpad.scanner.doc.DocumentationIndex.empty(),
            endpoints,
            "",
            "",
            List.of());
    }

    @Test
    void groupsEndpointsByControllerIntoInboundWorkflows() {
        var scan = scanWith(List.of(
            new Endpoint("GET", "/loans", "LoanDecisionController.list"),
            new Endpoint("POST", "/loans", "LoanDecisionController.decide"),
            new Endpoint("GET", "/users", "UserController.get")
        ));

        List<Workflow> workflows = discoverer.discover(scan);

        assertThat(workflows).hasSize(2);

        var loan = workflows.stream().filter(w -> w.id().equals("loandecisioncontroller"))
            .findFirst().orElseThrow();
        assertThat(loan.name()).isEqualTo("Loan Decision");
        assertThat(loan.type()).isEqualTo(WorkflowType.INBOUND_API);
        assertThat(loan.trigger()).isEqualTo("2 HTTP endpoints");
        assertThat(loan.steps()).containsExactly("GET /loans", "POST /loans");
        assertThat(loan.confidence()).isEqualTo(Confidence.DETERMINISTIC);
        assertThat(loan.evidence()).isNotEmpty();

        var user = workflows.stream().filter(w -> w.id().equals("usercontroller"))
            .findFirst().orElseThrow();
        assertThat(user.name()).isEqualTo("User");
        assertThat(user.trigger()).isEqualTo("GET /users");
    }

    @Test
    void handlerlessEndpointsBucketUnderHttpApi() {
        var scan = scanWith(List.of(new Endpoint("GET", "/ping", null)));

        var workflows = discoverer.discover(scan);

        assertThat(workflows).hasSize(1);
        assertThat(workflows.get(0).name()).isEqualTo("HTTP API");
    }

    @Test
    void noEndpointsYieldsNoWorkflows() {
        assertThat(discoverer.discover(scanWith(List.of()))).isEmpty();
        assertThat(discoverer.discover(null)).isEmpty();
    }

    @Test
    void discoversScheduledWorkflowsAlongsideHttp(@org.junit.jupiter.api.io.TempDir
                                                  java.nio.file.Path root) throws Exception {
        var src = root.resolve("Jobs.java");
        java.nio.file.Files.writeString(src, """
            class Jobs {
                @Scheduled(cron = "0 0 * * * *")
                void nightlyRollup() {}
            }
            """);

        var scan = new ProjectContext(
            "demo", root.toString(),
            new StackProfile("Java", "Maven", "Spring Boot", List.of()),
            List.of("Jobs.java"), List.of(), Map.of(), List.of(), Map.of(), List.of(), null,
            com.acltabontabon.launchpad.scanner.doc.DocumentationIndex.empty(),
            List.of(new Endpoint("GET", "/users", "UserController.get")),
            "", "", List.of());

        var workflows = discoverer.discover(scan);

        assertThat(workflows).hasSize(2);
        assertThat(workflows).anyMatch(w -> w.type() == WorkflowType.INBOUND_API);

        var scheduled = workflows.stream()
            .filter(w -> w.type() == WorkflowType.SCHEDULED).findFirst().orElseThrow();
        assertThat(scheduled.name()).isEqualTo("Nightly Rollup");
        assertThat(scheduled.trigger()).isEqualTo("@Scheduled nightlyRollup()");
        assertThat(scheduled.evidence()).isNotEmpty();
    }

    @Test
    void correlatesHttpWorkflowToCollaboratorsItTouches(@org.junit.jupiter.api.io.TempDir
                                                        java.nio.file.Path root) throws Exception {
        java.nio.file.Files.writeString(root.resolve("LoanController.java"), """
            class LoanController {
                private final LoanService loanService;
            }
            """);
        java.nio.file.Files.writeString(root.resolve("LoanService.java"), """
            class LoanService {
                private final LoanRepository loanRepository;
                private final CreditBureauClient creditBureauClient;
            }
            """);

        var scan = new ProjectContext(
            "demo", root.toString(),
            new StackProfile("Java", "Maven", "Spring Boot", List.of()),
            List.of("LoanController.java", "LoanService.java"),
            List.of(), Map.of(), List.of(), Map.of(), List.of(), null,
            com.acltabontabon.launchpad.scanner.doc.DocumentationIndex.empty(),
            List.of(new Endpoint("POST", "/loans", "LoanController.apply")),
            "", "", List.of());

        var workflow = discoverer.discover(scan).stream()
            .filter(w -> w.type() == WorkflowType.INBOUND_API).findFirst().orElseThrow();

        assertThat(workflow.touchedSystems()).containsExactly("LoanService");
        assertThat(workflow.dataEffects()).containsExactly("LoanRepository");
        assertThat(workflow.externalCalls()).containsExactly("CreditBureauClient");
    }
}
