package com.acltabontabon.launchpad.standards.infer;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.model.Confidence;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandardsInfererTest {

    private final StandardsInferer inferer = new StandardsInferer();

    private static void write(Path root, String rel, String body) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    private static ProjectContext scan(Path root, List<String> sources, List<Endpoint> endpoints) {
        return new ProjectContext(
            "demo", root.toString(),
            new StackProfile("Java", "Maven", "Spring Boot", List.of()),
            sources, List.of(), Map.of(), List.of(), Map.of(), List.of(), null,
            com.acltabontabon.launchpad.scanner.doc.DocumentationIndex.empty(),
            endpoints, "", "", List.of());
    }

    @Test
    void detectsDelegationPatternWhenControllersUseServices(@TempDir Path root) throws Exception {
        write(root, "com/acme/OrderController.java", """
            package com.acme;
            @RestController
            class OrderController {
                private final OrderService orderService;
            }
            """);
        write(root, "com/acme/OrderService.java", """
            package com.acme;
            class OrderService {
                private final OrderRepository orderRepository;
            }
            """);

        var inference = inferer.infer(scan(root,
            List.of("com/acme/OrderController.java", "com/acme/OrderService.java"),
            List.of(new Endpoint("GET", "/orders", "OrderController.list"))));

        assertThat(inference.patterns()).hasSize(1);
        var pattern = inference.patterns().get(0);
        assertThat(pattern.id()).isEqualTo("controller-service-delegation");
        assertThat(pattern.prevalence()).isEqualTo(1.0);
        assertThat(pattern.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(pattern.exemplars()).isNotEmpty();
        assertThat(inference.risks()).isEmpty();
    }

    @Test
    void flagsLayeringDriftWhenControllerReachesDataDirectly(@TempDir Path root) throws Exception {
        write(root, "com/acme/ReportController.java", """
            package com.acme;
            @RestController
            class ReportController {
                private final ReportRepository reportRepository;
            }
            """);

        var inference = inferer.infer(scan(root,
            List.of("com/acme/ReportController.java"),
            List.of(new Endpoint("GET", "/reports", "ReportController.list"))));

        // No service in between -> prevalence 0 and one drift risk.
        assertThat(inference.patterns().get(0).prevalence()).isEqualTo(0.0);
        assertThat(inference.risks()).hasSize(1);
        var risk = inference.risks().get(0);
        assertThat(risk.category()).isEqualTo("layering");
        assertThat(risk.description()).contains("ReportController");
        assertThat(risk.description()).contains("ReportRepository");
        assertThat(risk.confidence()).isEqualTo(Confidence.INFERRED);
    }

    @Test
    void suggestsGuardrailForPrevalentUndeclaredPattern(@TempDir Path root) throws Exception {
        write(root, "com/acme/OrderController.java", """
            package com.acme;
            @RestController
            class OrderController {
                private final OrderService orderService;
            }
            """);
        write(root, "com/acme/OrderService.java", "package com.acme;\nclass OrderService {}\n");

        var inference = inferer.infer(scan(root,
            List.of("com/acme/OrderController.java", "com/acme/OrderService.java"),
            List.of(new Endpoint("GET", "/orders", "OrderController.list"))));

        // Prevalence 1.0, no declared rules -> one suggested guardrail.
        assertThat(inference.inferredStandards()).hasSize(1);
        var suggestion = inference.inferredStandards().get(0);
        assertThat(suggestion.id()).isEqualTo("controller-service-delegation");
        assertThat(suggestion.proposedRule()).contains("delegate to a service layer");
        assertThat(suggestion.confidence()).isEqualTo(Confidence.LLM_SUGGESTED);
    }

    @Test
    void doesNotSuggestWhenRuleAlreadyDeclared(@TempDir Path root) throws Exception {
        write(root, "com/acme/OrderController.java", """
            package com.acme;
            @RestController
            class OrderController {
                private final OrderService orderService;
            }
            """);
        write(root, "com/acme/OrderService.java", "package com.acme;\nclass OrderService {}\n");

        var inference = inferer.infer(scan(root,
            List.of("com/acme/OrderController.java", "com/acme/OrderService.java"),
            List.of(new Endpoint("GET", "/orders", "OrderController.list"))),
            java.util.Set.of("controller-service-delegation"));

        assertThat(inference.inferredStandards()).isEmpty();
        // The pattern is still detected; only the suggestion is suppressed.
        assertThat(inference.patterns()).hasSize(1);
    }

    @Test
    void emptyWhenNoControllers(@TempDir Path root) throws Exception {
        write(root, "com/acme/Helper.java", "package com.acme;\nclass Helper {}\n");
        var inference = inferer.infer(scan(root, List.of("com/acme/Helper.java"), List.of()));
        assertThat(inference).isEqualTo(StandardsInferer.Inference.EMPTY);
    }

    @Test
    void emptyOnNullScan() {
        assertThat(inferer.infer(null)).isEqualTo(StandardsInferer.Inference.EMPTY);
    }
}
