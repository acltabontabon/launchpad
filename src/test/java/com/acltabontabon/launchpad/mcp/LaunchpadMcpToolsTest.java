package com.acltabontabon.launchpad.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.audit.AuditService;
import com.acltabontabon.launchpad.audit.MarkdownAuditWriter;
import com.acltabontabon.launchpad.audit.SarifWriter;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.model.VirtualProjectContextStore;
import com.acltabontabon.launchpad.scanner.ProjectSupportDetector;
import com.acltabontabon.launchpad.scanner.ScanStore;
import com.acltabontabon.launchpad.springboot.detectors.SpringBootMavenSupportSignal;
import com.acltabontabon.launchpad.springboot.scanner.ProjectScanner;
import com.acltabontabon.launchpad.standards.RemoteStandardsFetcher;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration coverage for the MCP tool surface in {@link LaunchpadMcpTools}.
 * Targets the spring-boot fixture under src/test/resources/fixtures/ so the
 * behaviour exercised is the same one MCP clients (Claude Code, Cursor, ...)
 * see at runtime. Runs without Ollama: only deterministic scan + standards +
 * (zero-rule) audit paths are touched.
 */
class LaunchpadMcpToolsTest {

    @TempDir
    Path workspace;

    private LaunchpadMcpTools tools;
    private ProjectRegistry registry;
    private ScanStore scanStore;
    private Path projectRoot;

    @BeforeEach
    void setUp() throws IOException {
        // ProjectRegistry / LaunchpadSettings derive their on-disk paths from
        // user.home at construction. Redirect to a per-test subdir so each test
        // gets a clean registry and the user's real ~/.launchpad/ stays untouched.
        var home = workspace.resolve("home");
        Files.createDirectories(home);
        System.setProperty("user.home", home.toString());

        var fixture = new File("src/test/resources/fixtures/spring-boot").getAbsoluteFile().toPath();
        projectRoot = workspace.resolve("spring-boot");
        copyDirectory(fixture, projectRoot);

        var settings = new LaunchpadSettings("auto", "http://localhost:11434",
            "qwen2.5-coder:7b-instruct", "", event -> { });
        var fetcher = new RemoteStandardsFetcher(settings);
        var standardsLoader = new StandardsLoader(fetcher, settings);
        var scanner = ProjectScanner.forTesting();
        scanStore = new ScanStore();
        var auditService = new AuditService(standardsLoader, List.of(),
            new SarifWriter(), new MarkdownAuditWriter());
        registry = new ProjectRegistry();
        var detector = new ProjectSupportDetector(List.of(new SpringBootMavenSupportSignal()));
        var modelStore = new VirtualProjectContextStore();

        tools = new LaunchpadMcpTools(scanner, scanStore, auditService, standardsLoader,
            registry, detector, modelStore, 512L);

        registry.register(projectRoot, "Spring Boot");
    }

    @Test
    void listProjectsReturnsRegisteredEntries() {
        var out = tools.listProjects(null);
        assertThat(out).containsEntry("count", 1);
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) out.get("projects");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("path")).isEqualTo(projectRoot.toString());
        assertThat(entries.get(0).get("stack")).isEqualTo("Spring Boot");
    }

    @Test
    void listProjectsFilteredByWorkspaceReturnsEmptyWhenNoMatch() {
        var out = tools.listProjects("nonexistent-workspace");
        assertThat(out).containsEntry("count", 0);
        assertThat(out).containsEntry("workspaceFilter", "nonexistent-workspace");
    }

    @Test
    void scanProjectReturnsStackDependenciesAndPackages() {
        var out = tools.scanProject(projectRoot.toString());
        assertThat(out).containsKey("name");
        assertThat(out.get("rootPath")).isEqualTo(projectRoot.toString());
        @SuppressWarnings("unchecked")
        var stack = (Map<String, Object>) out.get("stack");
        assertThat(stack.get("language")).isEqualTo("Java");
        assertThat(stack.get("buildTool")).isEqualTo("Maven");
        assertThat(stack.get("framework")).isEqualTo("Spring Boot");
        @SuppressWarnings("unchecked")
        var deps = (List<Map<String, Object>>) out.get("dependencies");
        assertThat(deps).isNotEmpty();
        assertThat(deps).anySatisfy(d ->
            assertThat(d.get("name").toString()).contains("spring-boot-starter-web"));
        assertThat((Integer) out.get("sourceFileCount")).isGreaterThan(0);
    }

    @Test
    void scanProjectErrorsWhenNoProjectArgumentAndNoDefault() {
        var out = tools.scanProject(null);
        var err = errorEnvelope(out);
        assertThat(err).containsEntry("code", "no_project_specified");
        assertThat(err).containsEntry("type", "INVALID_ARGUMENT");
        assertThat(details(err)).containsKey("availableProjects");
    }

    @Test
    void scanProjectErrorsWhenUnknownName() {
        var out = tools.scanProject("definitely-not-registered");
        var err = errorEnvelope(out);
        assertThat(err).containsEntry("code", "unknown_project");
        assertThat(err).containsEntry("type", "NOT_FOUND");
        assertThat(details(err)).containsEntry("requested", "definitely-not-registered");
    }

    @Test
    void scanProjectErrorsOnUnsupportedProject() throws IOException {
        var empty = workspace.resolve("empty");
        Files.createDirectories(empty);
        var out = tools.scanProject(empty.toString());
        assertThat(errorEnvelope(out)).containsEntry("code", "unsupported_project");
    }

    @Test
    void getStandardsReturnsEmptyCollectionsWhenNoStandardsPack() {
        var out = tools.getStandards(projectRoot.toString());
        assertThat(out).containsKeys("rules", "skills", "checklists");
        assertThat((List<?>) out.get("rules")).isEmpty();
        assertThat((List<?>) out.get("skills")).isEmpty();
        assertThat((List<?>) out.get("checklists")).isEmpty();
    }

    @Test
    void getAuditFindingsReturnsShapeWithNoRules() {
        var out = tools.getAuditFindings(projectRoot.toString());
        assertThat(out).containsEntry("rulesAudited", 0);
        assertThat(out).containsEntry("totalFindings", 0);
        assertThat((List<?>) out.get("findings")).isEmpty();
    }

    @Test
    void compareStandardsBucketsAreAllEmptyWhenBothProjectsHaveNoPack() {
        var out = tools.compareStandards(projectRoot.toString(), projectRoot.toString());
        for (var kind : List.of("rules", "skills", "checklists")) {
            @SuppressWarnings("unchecked")
            var bucket = (Map<String, Object>) out.get(kind);
            assertThat((List<?>) bucket.get("common")).isEmpty();
            assertThat((List<?>) bucket.get("divergent")).isEmpty();
            assertThat((List<?>) bucket.get("aOnly")).isEmpty();
            assertThat((List<?>) bucket.get("bOnly")).isEmpty();
        }
    }

    @Test
    void listDocumentationReturnsPagesFromFixture() {
        var out = tools.listDocumentation(projectRoot.toString(), null);
        @SuppressWarnings("unchecked")
        var pages = (List<Map<String, Object>>) out.get("pages");
        assertThat(pages).extracting(p -> p.get("path"))
            .contains("CHANGELOG.md", "CONTRIBUTING.md",
                "docs/index.md", "docs/guide/setup.md",
                "docs/architecture/overview.md");
    }

    @Test
    void listDocumentationRejectsUnknownPurpose() {
        var out = tools.listDocumentation(projectRoot.toString(), "no-such-purpose");
        assertThat(errorEnvelope(out)).containsEntry("code", "invalid_purpose");
    }

    @Test
    void errorEnvelopeShapeIsStandardised() {
        var out = tools.listDocumentation(projectRoot.toString(), "no-such-purpose");
        assertThat(out).hasSize(1).containsOnlyKeys("error");
        var err = errorEnvelope(out);
        assertThat(err).containsKeys("code", "type", "message");
        assertThat(err.get("type")).isInstanceOf(String.class);
        assertThat(err.get("message")).asString().isNotBlank();
    }

    @Test
    void findDocumentationLocatesKeywordInScannedProject() {
        tools.scanProject(projectRoot.toString());
        var out = tools.findDocumentation("setup", projectRoot.toString());
        assertThat(out).containsEntry("query", "setup");
        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) out.get("results");
        assertThat(results).isNotEmpty();
        assertThat((Integer) out.get("totalMatches")).isGreaterThan(0);
    }

    @Test
    void findDocumentationRejectsBlankQuery() {
        var out = tools.findDocumentation("   ", projectRoot.toString());
        assertThat(errorEnvelope(out)).containsEntry("code", "missing_query");
    }

    @Test
    void getDocumentationReadsKnownDocPage() {
        var out = tools.getDocumentation(projectRoot.toString(), "docs/index.md");
        assertThat(out).containsEntry("path", "docs/index.md");
        assertThat(out.get("content")).asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.STRING).isNotEmpty();
        assertThat(out).containsKey("purpose");
    }

    @Test
    void getDocumentationRejectsPathNotInDocsIndex() {
        var out = tools.getDocumentation(projectRoot.toString(), "pom.xml");
        assertThat(errorEnvelope(out)).containsEntry("code", "path_not_in_docs_index");
    }

    @Test
    void modelBackedToolsReturnNoProjectModelErrorBeforeSynthesis() {
        for (var out : List.of(
            tools.getWorkflows(projectRoot.toString()),
            tools.getSystems(projectRoot.toString()),
            tools.getRisks(projectRoot.toString()),
            tools.getProjectOverview(projectRoot.toString()))) {
            assertThat(errorEnvelope(out)).containsEntry("code", "no_project_model");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorEnvelope(Map<String, Object> out) {
        var raw = out.get("error");
        assertThat(raw).as("expected nested `error` envelope").isInstanceOf(Map.class);
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> err) {
        return (Map<String, Object>) err.get("details");
    }

    @Test
    void scanProjectHonoursScanStoreFreshnessOnSecondCall() throws IOException {
        var first = tools.scanProject(projectRoot.toString());
        int firstSourceCount = (Integer) first.get("sourceFileCount");
        assertThat(firstSourceCount).isGreaterThan(0);
        var scanFile = scanStore.scanFile(projectRoot);
        assertThat(Files.isRegularFile(scanFile)).isTrue();
        assertThat(scanStore.isFresh(projectRoot, Duration.ofHours(24))).isTrue();

        // Mutate the source tree so a fresh scan would yield a different
        // sourceFileCount; the cached scan.json must still drive the response.
        Files.delete(projectRoot.resolve(
            "src/main/java/com/example/orders/OrderController.java"));
        var second = tools.scanProject(projectRoot.toString());
        assertThat((Integer) second.get("sourceFileCount"))
            .as("second call within freshness window must hit cached scan")
            .isEqualTo(firstSourceCount);

        // Age the cache past the 24h freshness window; the next call must
        // re-scan and pick up the smaller source tree.
        Files.setLastModifiedTime(scanFile,
            FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        assertThat(scanStore.isFresh(projectRoot, Duration.ofHours(24))).isFalse();
        var third = tools.scanProject(projectRoot.toString());
        assertThat((Integer) third.get("sourceFileCount"))
            .as("expired cache must trigger a fresh scan")
            .isLessThan(firstSourceCount);
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
