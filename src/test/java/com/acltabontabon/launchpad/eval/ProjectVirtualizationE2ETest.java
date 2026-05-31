package com.acltabontabon.launchpad.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.model.Confidence;
import com.acltabontabon.launchpad.model.ProjectContextAssembler;
import com.acltabontabon.launchpad.model.Workflow;
import com.acltabontabon.launchpad.model.WorkflowType;
import com.acltabontabon.launchpad.springboot.scanner.ProjectScanner;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof: scan a real Spring Boot fixture, assemble the
 * virtualized model, and assert the full pipeline (scan -> workflows ->
 * correlation -> standards inference) produces real synthesized content.
 * <p>
 * The {@code spring-boot} fixture is deliberately shaped to exercise both
 * sides of the inference engine: a delegating
 * {@code UserController -> UserService -> UserRepository} chain (the
 * controller-service-delegation pattern), and an empty {@code OrderController}
 * that exists but reaches nothing (no drift, no delegation). The assertions
 * verify the model picks up what the synthesized output actually relies on,
 * without pinning to brittle text - so the test stays green as wording evolves.
 */
class ProjectVirtualizationE2ETest {

    @Test
    void scansFixtureAndAssemblesVirtualizedModel() throws Exception {
        var root = FixtureSupport.fixturePath("spring-boot");
        var scan = ProjectScanner.forTesting().scan(root.toString(), msg -> { });

        var model = new ProjectContextAssembler().assemble(scan, "", "2026-05-31T00:00:00Z");

        // Identity is populated from the deterministic scan.
        assertThat(model.identity().name()).isNotBlank();
        assertThat(model.identity().rootPath()).contains("spring-boot");
        assertThat(model.identity().contentHash()).isNotBlank();
        assertThat(model.identity().primaryStack()).containsIgnoringCase("Spring Boot");

        // Architecture detected layers from package names.
        assertThat(model.architecture().confidence()).isEqualTo(Confidence.DETERMINISTIC);
        assertThat(model.architecture().modules()).isNotEmpty();

        // Operations carries the known build commands for the gated stack.
        assertThat(model.operations().buildCommands()).contains("./mvnw test");
    }

    @Test
    void discoversInboundWorkflowsAndCorrelatesCollaborators() throws Exception {
        var root = FixtureSupport.fixturePath("spring-boot");
        var scan = ProjectScanner.forTesting().scan(root.toString(), msg -> { });
        var model = new ProjectContextAssembler().assemble(scan, "", "t");

        // The User controller has a real endpoint and surfaces as an
        // inbound-API workflow; the empty OrderController has no @*Mapping so
        // the endpoint extractor finds nothing - workflow discovery is
        // endpoint-driven by design.
        var inbound = model.workflows().stream()
            .filter(w -> w.type() == WorkflowType.INBOUND_API)
            .toList();
        assertThat(inbound).extracting(Workflow::name).contains("User");

        // The User workflow correlates through to its real collaborators - the
        // proof that scan + correlation graph wire up end-to-end.
        var user = inbound.stream().filter(w -> w.name().equals("User")).findFirst().orElseThrow();
        assertThat(user.touchedSystems()).contains("UserService");
        assertThat(user.dataEffects()).contains("UserRepository");
    }

    @Test
    void infersDelegationPatternFromObservedConsistency() throws Exception {
        var root = FixtureSupport.fixturePath("spring-boot");
        var scan = ProjectScanner.forTesting().scan(root.toString(), msg -> { });
        var model = new ProjectContextAssembler().assemble(scan, "", "t");

        // The fixture intentionally has one delegating controller and one empty
        // one, so the inferer detects the pattern but does not flag drift
        // (the empty controller reaches no data store either).
        var delegation = model.standards().detectedPatterns().stream()
            .filter(p -> p.id().equals("controller-service-delegation"))
            .findFirst()
            .orElseThrow();
        assertThat(delegation.prevalence()).isGreaterThan(0.0);
        assertThat(delegation.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(delegation.exemplars()).isNotEmpty();
    }
}
