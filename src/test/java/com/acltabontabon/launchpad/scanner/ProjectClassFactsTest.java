package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectClassFactsTest {

    @Test
    void enrichesInterfacesWithImplsAndControllersWithRoutes(@TempDir Path root) throws Exception {
        write(root, "src/main/java/foo/RiskModel.java", """
            package foo;
            public interface RiskModel { double score(int x); }
            """);
        write(root, "src/main/java/foo/PrimeRiskModel.java", """
            package foo;
            public class PrimeRiskModel implements RiskModel {
                public double score(int x) { return 1.0; }
            }
            """);
        write(root, "src/main/java/foo/SubprimeRiskModel.java", """
            package foo;
            public class SubprimeRiskModel implements RiskModel {
                public double score(int x) { return 0.2; }
            }
            """);
        write(root, "src/main/java/foo/HelloController.java", """
            package foo;
            @RestController
            public class HelloController {
                @GetMapping("/hello") public String hi() { return "hi"; }
            }
            """);

        var sources = List.of(
            "src/main/java/foo/RiskModel.java",
            "src/main/java/foo/PrimeRiskModel.java",
            "src/main/java/foo/SubprimeRiskModel.java",
            "src/main/java/foo/HelloController.java");
        var endpoints = List.of(new Endpoint("GET", "/hello", "HelloController.hi"));

        var facts = ProjectClassFacts.collect(root, sources, endpoints);

        var byName = facts.stream().collect(
            java.util.stream.Collectors.toMap(ClassFact::name, f -> f));
        assertThat(byName).containsKeys("RiskModel", "PrimeRiskModel", "SubprimeRiskModel", "HelloController");

        var riskModel = byName.get("RiskModel");
        assertThat(riskModel.kind()).isEqualTo(ClassFact.Kind.INTERFACE);
        assertThat(riskModel.impls()).containsExactlyInAnyOrder("PrimeRiskModel", "SubprimeRiskModel");

        var ctrl = byName.get("HelloController");
        assertThat(ctrl.kind()).isEqualTo(ClassFact.Kind.REST_CONTROLLER);
        assertThat(ctrl.routes()).containsExactly("GET /hello");
    }

    @Test
    void emptyWhenNoSources(@TempDir Path root) {
        assertThat(ProjectClassFacts.collect(root, List.of(), List.of())).isEmpty();
    }

    @Test
    void groupByLeafPackagePreservesInsertionOrder(@TempDir Path root) throws Exception {
        write(root, "src/main/java/a/AaaController.java", "@RestController public class AaaController {}\n");
        write(root, "src/main/java/b/Bbb.java", "public record Bbb() {}\n");
        write(root, "src/main/java/a/Aaa2.java", "public record Aaa2() {}\n");

        var sources = List.of(
            "src/main/java/a/AaaController.java",
            "src/main/java/b/Bbb.java",
            "src/main/java/a/Aaa2.java");
        var facts = ProjectClassFacts.collect(root, sources, List.of());
        var grouped = ProjectClassFacts.groupByLeafPackage(facts);

        // Insertion order: "a" (first), then "b".
        assertThat(grouped.keySet()).containsExactly("a", "b");
        assertThat(grouped.get("a")).extracting(ClassFact::name)
            .containsExactly("AaaController", "Aaa2");
    }

    private static void write(Path root, String rel, String content) throws Exception {
        var p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
