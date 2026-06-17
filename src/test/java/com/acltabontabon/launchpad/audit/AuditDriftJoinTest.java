package com.acltabontabon.launchpad.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.scanner.PackageSummary;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.RemoteStandardsFetcher;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import com.acltabontabon.launchpad.standards.index.StandardsIndexAssembler;
import com.acltabontabon.launchpad.standards.index.StandardsRuleEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The core #81 contract: an audit finding's {@code ruleHash} is the same value as
 * the matching rule's {@code contentHash} in the standards sidecar. A consumer can
 * compare the two to detect findings produced against stale standards text.
 */
class AuditDriftJoinTest {

    private final StandardsIndexAssembler assembler = new StandardsIndexAssembler();

    @Test
    void findingRuleHashEqualsSidecarContentHash(@TempDir Path projectRoot) throws Exception {
        var standards = projectRoot.resolve(".launchpad/standards");
        Files.createDirectories(standards);
        Files.writeString(standards.resolve("rules.yml"), """
            version: 1
            rules:
              - id: no-autowired
                title: No field injection
                severity: must
                content: Use constructor injection.
                rationale: Field injection hides dependencies.
                check:
                  kind: forbid-pattern
                  pattern: "@Autowired"
                  includes:
                    - "**/*.java"
            """);

        var relativePath = "src/main/java/com/example/UserService.java";
        write(projectRoot, relativePath, """
            package com.example;
            public class UserService {
                @Autowired
                private Repo repo;
            }
            """);

        var loader = new StandardsLoader(noRemote(), null);
        var audit = new AuditService(loader, List.of(new PatternChecker()),
            new SarifWriter(), new MarkdownAuditWriter());

        var result = audit.run(contextOf(relativePath), projectRoot);
        assertThat(result.findings()).isNotEmpty();
        var finding = result.findings().get(0);
        assertThat(finding.ruleId()).isEqualTo("no-autowired");
        assertThat(finding.ruleHash()).isNotBlank();

        // Same hash the sidecar stamps for the same rule.
        var index = assembler.assemble(loader.loadResolvedStandards(projectRoot), "2026-06-17T00:00:00Z");
        var entry = index.rules().stream()
            .filter(r -> r.id().equals("no-autowired")).findFirst().orElseThrow();
        assertThat(finding.ruleHash()).isEqualTo(entry.contentHash());

        // And the same value lands in the SARIF result properties.
        var sarif = new ObjectMapper().readTree(result.sarifPath().toFile());
        var props = sarif.get("runs").get(0).get("results").get(0).get("properties");
        assertThat(props.get("ruleHash").asText()).isEqualTo(entry.contentHash());
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        var file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static ProjectContext contextOf(String... files) {
        return new ProjectContext(
            "demo", "/tmp/demo",
            new StackProfile("Java", "Maven", "Spring Boot", List.of()),
            List.of(files), List.of(), Map.of(),
            List.of(new Dependency("x", "1", "runtime")),
            Map.of(), List.<PackageSummary>of(), null);
    }

    private static RemoteStandardsFetcher noRemote() {
        var m = mock(RemoteStandardsFetcher.class);
        when(m.ensureCache()).thenReturn(Optional.empty());
        return m;
    }
}
