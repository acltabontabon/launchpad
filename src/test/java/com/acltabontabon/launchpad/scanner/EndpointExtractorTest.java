package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EndpointExtractorTest {

    @Test
    void parsesRequestMappingClassPathAndMethodVerbs() {
        var source = """
            @RestController
            @RequestMapping("/loan-decision")
            public class LoanDecisionController {

                @PostMapping
                public LoanDecision decide(@RequestBody LoanApplication app) { return null; }

                @GetMapping
                public LoanDecision benchmark() { return null; }
            }
            """;

        var endpoints = EndpointExtractor.parseControllerFile(source);

        assertThat(endpoints).hasSize(2);
        assertThat(endpoints).anyMatch(e -> e.method().equals("POST")
            && e.path().equals("/loan-decision")
            && e.handler().equals("LoanDecisionController.decide"));
        assertThat(endpoints).anyMatch(e -> e.method().equals("GET")
            && e.path().equals("/loan-decision")
            && e.handler().equals("LoanDecisionController.benchmark"));
    }

    @Test
    void joinsClassAndMethodPaths() {
        var source = """
            @RestController
            @RequestMapping("/api/v1")
            public class ItemsController {

                @GetMapping("/items")
                public String list() { return null; }

                @GetMapping("/items/{id}")
                public String byId() { return null; }
            }
            """;

        var endpoints = EndpointExtractor.parseControllerFile(source);

        assertThat(endpoints).extracting(Endpoint::path)
            .containsExactlyInAnyOrder("/api/v1/items", "/api/v1/items/{id}");
    }

    @Test
    void handlesValueAttributeForm() {
        var source = """
            @RestController
            public class HelloController {
                @GetMapping(value = "/hello")
                public String hello() { return "hi"; }
            }
            """;

        var endpoints = EndpointExtractor.parseControllerFile(source);
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).path()).isEqualTo("/hello");
        assertThat(endpoints.get(0).method()).isEqualTo("GET");
    }

    @Test
    void noMappingsReturnsEmpty() {
        var source = """
            public class PlainPojo {
                public String hello() { return "hi"; }
            }
            """;
        assertThat(EndpointExtractor.parseControllerFile(source)).isEmpty();
    }

    @Test
    void getControllerSourcesIsPopulatedAfterExtract(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var dir = root.resolve("src/main/java/foo");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("FooController.java"), """
            package foo;
            @RestController
            @RequestMapping("/foo")
            public class FooController {
                @GetMapping public String hi() { return "hi"; }
            }
            """);

        var extractor = new EndpointExtractor();
        var endpoints = extractor.extract(root,
            java.util.List.of("src/main/java/foo/FooController.java"));
        assertThat(endpoints).hasSize(1);
        var sources = extractor.getControllerSources();
        assertThat(sources).containsKey("src/main/java/foo/FooController.java");
        assertThat(sources.get("src/main/java/foo/FooController.java")).contains("FooController");
    }

    @Test
    void bareGetMappingDefaultsPathToClassBase() {
        var source = """
            @RestController
            @RequestMapping("/decisions")
            public class DecisionsController {
                @GetMapping
                public String all() { return null; }
            }
            """;

        var endpoints = EndpointExtractor.parseControllerFile(source);
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).path()).isEqualTo("/decisions");
    }
}
