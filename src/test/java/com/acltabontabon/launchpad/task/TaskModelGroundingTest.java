package com.acltabontabon.launchpad.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.model.Confidence;
import com.acltabontabon.launchpad.model.Risk;
import com.acltabontabon.launchpad.model.RiskSeverity;
import com.acltabontabon.launchpad.model.SystemComponent;
import com.acltabontabon.launchpad.model.VirtualProjectContext;
import com.acltabontabon.launchpad.model.Workflow;
import com.acltabontabon.launchpad.model.WorkflowType;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskModelGroundingTest {

    private static Workflow workflow(String id, String name, List<String> systems) {
        return new Workflow(id, name, "", WorkflowType.INBOUND_API,
            "POST /" + id, List.of("POST /" + id),
            systems, List.of(), List.of(), Confidence.DETERMINISTIC, List.of());
    }

    private static SystemComponent system(String name) {
        return new SystemComponent(name.toLowerCase(), name, "", List.of(), List.of(name),
            List.of(), Confidence.DETERMINISTIC, List.of());
    }

    private static VirtualProjectContext model(List<SystemComponent> systems,
                                               List<Workflow> workflows, List<Risk> risks) {
        return new VirtualProjectContext(null, null, systems, workflows, null, null, null, risks);
    }

    @Test
    void groundsTaskToMatchingWorkflowAndItsSystems() {
        var model = model(
            List.of(system("LoanService"), system("UserService")),
            List.of(workflow("loan", "Loan Decision", List.of("LoanService", "CreditBureauClient"))),
            List.of());

        var g = TaskModelGrounding.ground("Add validation to the loan decision endpoint", model);

        assertThat(g.relevantWorkflows()).extracting(Workflow::name).containsExactly("Loan Decision");
        // The matched workflow's touched systems are inherited as affected systems.
        assertThat(g.affectedSystems()).contains("LoanService", "CreditBureauClient");
        assertThat(g.markdown()).contains("## Execution context");
        assertThat(g.markdown()).contains("### Relevant workflows");
        assertThat(g.markdown()).contains("Loan Decision");
    }

    @Test
    void surfacesSystemSpecificRiskWhenSystemAffected() {
        var risk = new Risk("layering-drift-loancontroller", "layering", RiskSeverity.MEDIUM,
            "LoanController reaches a data store directly.", List.of(),
            List.of("loanservice"), "Introduce a service.", Confidence.INFERRED);
        var model = model(
            List.of(system("LoanService")),
            List.of(workflow("loan", "Loan Decision", List.of("LoanService"))),
            List.of(risk));

        var g = TaskModelGrounding.ground("change the loan flow", model);

        assertThat(g.riskWatchlist()).hasSize(1);
        assertThat(g.markdown()).contains("### Risk watchlist");
        assertThat(g.markdown()).contains("layering");
    }

    @Test
    void emptyGroundingProducesNoMarkdown() {
        var model = model(
            List.of(system("BillingService")),
            List.of(workflow("billing", "Billing", List.of("BillingService"))),
            List.of());

        // Task shares no significant words with any model entry.
        var g = TaskModelGrounding.ground("tweak the homepage banner colour", model);

        assertThat(g.isEmpty()).isTrue();
        assertThat(g.markdown()).isEmpty();
    }

    @Test
    void nullModelYieldsEmpty() {
        var g = TaskModelGrounding.ground("anything", null);
        assertThat(g.isEmpty()).isTrue();
        assertThat(g.markdown()).isEmpty();
    }
}
