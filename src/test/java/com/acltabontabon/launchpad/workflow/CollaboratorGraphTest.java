package com.acltabontabon.launchpad.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollaboratorGraphTest {

    private static void write(Path root, String rel, String body) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    @Test
    void walksTransitivelyAndPartitionsByKind(@TempDir Path root) throws Exception {
        write(root, "com/acme/OrderController.java", """
            package com.acme;
            class OrderController {
                private final OrderService orderService;
            }
            """);
        write(root, "com/acme/OrderService.java", """
            package com.acme;
            class OrderService {
                private final OrderRepository orderRepository;
                private final PricingClient pricingClient;
            }
            """);
        write(root, "com/acme/OrderRepository.java", "package com.acme;\ninterface OrderRepository {}\n");

        var graph = CollaboratorGraph.build(root, List.of(
            "com/acme/OrderController.java",
            "com/acme/OrderService.java",
            "com/acme/OrderRepository.java"));

        var touches = graph.touches("OrderController");

        // OrderController -> OrderService (system), transitively -> OrderRepository
        // (data) and PricingClient (external).
        assertThat(touches.systems()).containsExactly("OrderService");
        assertThat(touches.dataEffects()).containsExactly("OrderRepository");
        assertThat(touches.externalCalls()).containsExactly("PricingClient");
    }

    @Test
    void unknownRootYieldsEmpty(@TempDir Path root) {
        var graph = CollaboratorGraph.build(root, List.of());
        assertThat(graph.touches("Nope")).isEqualTo(CollaboratorGraph.Collaborators.EMPTY);
        assertThat(graph.touches(null)).isEqualTo(CollaboratorGraph.Collaborators.EMPTY);
    }

    @Test
    void toleratesCyclesWithoutLooping(@TempDir Path root) throws Exception {
        write(root, "A.java", "class A { private final BService bService; }\n");
        write(root, "BService.java", "class BService { private final AService aService; }\n");
        write(root, "AService.java", "class AService { private final BService bService; }\n");

        var graph = CollaboratorGraph.build(root, List.of("A.java", "BService.java", "AService.java"));

        // A references BService, which cycles with AService; the walk terminates
        // and collects both services without looping.
        assertThat(graph.touches("A").systems()).containsExactly("AService", "BService");
    }
}
