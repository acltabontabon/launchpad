package com.acltabontabon.launchpad.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.audit.AuditService;
import com.acltabontabon.launchpad.audit.MarkdownAuditWriter;
import com.acltabontabon.launchpad.audit.SarifWriter;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.model.VirtualProjectContextStore;
import com.acltabontabon.launchpad.model.graph.ProjectModelStore;
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

    // Kept so individual tests can rebuild a tool instance in a forced response
    // mode (the default is references; some assertions need inline).
    private ProjectScanner scanner;
    private AuditService auditService;
    private StandardsLoader standardsLoader;
    private ProjectSupportDetector detector;
    private VirtualProjectContextStore modelStore;

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
        standardsLoader = new StandardsLoader(fetcher, settings);
        scanner = ProjectScanner.forTesting();
        scanStore = new ScanStore();
        auditService = new AuditService(standardsLoader, List.of(),
            new SarifWriter(), new MarkdownAuditWriter());
        registry = new ProjectRegistry();
        detector = new ProjectSupportDetector(List.of(new SpringBootMavenSupportSignal()));
        modelStore = new VirtualProjectContextStore();

        // Default instance: no override, so references mode is active by default.
        tools = toolsWith(null);

        registry.register(projectRoot, "Spring Boot");
    }

    /** Build a tool instance with the given response-mode property (null = default). */
    private LaunchpadMcpTools toolsWith(String responseModeProperty) {
        return new LaunchpadMcpTools(scanner, scanStore, auditService, standardsLoader,
            registry, detector, modelStore, new ProjectModelStore(), 512L, responseModeProperty);
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
        var out = tools.getStandards(projectRoot.toString(), null);
        assertThat(out).containsKeys("rules", "skills", "checklists");
        assertThat((List<?>) out.get("rules")).isEmpty();
        assertThat((List<?>) out.get("skills")).isEmpty();
        assertThat((List<?>) out.get("checklists")).isEmpty();
    }

    @Test
    void getStandardsRejectsIncompatiblePackSchema() throws IOException {
        var standards = projectRoot.resolve(".launchpad/standards");
        Files.createDirectories(standards);
        Files.writeString(standards.resolve("standards-pack.yml"), """
            schemaVersion: 99
            id: future-pack
            version: 1.0.0
            includes:
              rules:
                - rules/main.yml
            """);

        var out = tools.getStandards(projectRoot.toString(), null);
        var err = errorEnvelope(out);
        assertThat(err).containsEntry("code", "incompatible_pack_schema");
        assertThat(err).containsEntry("type", "UNSUPPORTED");
        assertThat(err).containsKey("remediation");
        assertThat(details(err)).containsEntry("found", 99);
        assertThat(details(err)).containsEntry("supported", "1");
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
    void getDocumentationReadsKnownDocPageInInlineMode() {
        var out = toolsWith("inline").getDocumentation(projectRoot.toString(), "docs/index.md");
        assertThat(out).containsEntry("path", "docs/index.md");
        assertThat(out.get("content")).asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.STRING).isNotEmpty();
        assertThat(out).containsKey("purpose");
        // Inline mode must not leak references-envelope fields.
        assertThat(out).doesNotContainKeys("responseMode", "contentHash", "hint");
    }

    @Test
    void getDocumentationReturnsReferenceByDefault() {
        var out = tools.getDocumentation(projectRoot.toString(), "docs/index.md");
        assertThat(out).containsEntry("responseMode", "references");
        assertThat(out).containsEntry("path", "docs/index.md");
        assertThat(out).containsEntry("ref", "docs/index.md");
        assertThat(out).containsKeys("title", "format", "purpose", "sizeBytes", "contentHash", "hint");
        assertThat(out.get("contentHash").toString()).startsWith("sha256:");
        // The body itself is omitted - that is the whole point of references mode.
        assertThat(out).doesNotContainKey("content");
    }

    @Test
    void defaultResponseModeIsReferences() {
        assertThat(tools.responseMode()).isEqualTo(McpResponseMode.REFERENCES);
        assertThat(toolsWith("inline").responseMode()).isEqualTo(McpResponseMode.INLINE);
        // Unknown value falls back to the references default rather than erroring.
        assertThat(toolsWith("nonsense").responseMode()).isEqualTo(McpResponseMode.REFERENCES);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStandardsReturnsReferencesByDefault() throws IOException {
        writeStandardsPack();

        var out = tools.getStandards(projectRoot.toString(), null);
        assertThat(out).containsEntry("responseMode", "references");
        assertThat(out).containsEntry("index", ".launchpad/standards.index.json");
        assertThat(out).containsKey("hint");
        var companions = (Map<String, Object>) out.get("companions");
        assertThat(companions).containsEntry("rules", ".ai/engineering-rules.md")
            .containsEntry("skills", ".ai/skills.md")
            .containsEntry("checklists", ".ai/checklists.md");

        var rule = ((List<Map<String, Object>>) out.get("rules")).get(0);
        assertThat(rule).containsKeys("id", "title", "severity", "auditable", "contentHash",
            "summary", "ref", "path", "anchor");
        assertThat(rule).doesNotContainKeys("description", "rationale");
        assertThat(rule.get("anchor")).isEqualTo("java-no-field-injection");
        assertThat(rule.get("ref")).isEqualTo(".ai/engineering-rules.md#java-no-field-injection");
        assertThat(rule.get("path")).isEqualTo(".ai/engineering-rules.md");

        var skill = ((List<Map<String, Object>>) out.get("skills")).get(0);
        assertThat(skill).containsKeys("id", "title", "triggerSummary", "contentHash", "summary",
            "ref", "path", "anchor");
        assertThat(skill).doesNotContainKey("steps");

        var checklist = ((List<Map<String, Object>>) out.get("checklists")).get(0);
        assertThat(checklist).containsKeys("id", "title", "itemCount", "contentHash", "summary",
            "ref", "path", "anchor");
        assertThat(checklist).containsEntry("itemCount", 1);
        assertThat(checklist).doesNotContainKey("items");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStandardsInlineModePreservesLegacyShape() throws IOException {
        writeStandardsPack();

        var out = toolsWith("inline").getStandards(projectRoot.toString(), null);
        assertThat(out).containsOnlyKeys("rules", "skills", "checklists");
        var rule = ((List<Map<String, Object>>) out.get("rules")).get(0);
        assertThat(rule).containsKeys("id", "title", "severity", "description", "rationale",
            "auditable");
        assertThat(rule).doesNotContainKeys("ref", "contentHash", "summary");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compareStandardsReferencesCarryHashesNotBodies() throws IOException {
        writeStandardsPack();

        var out = tools.compareStandards(projectRoot.toString(), projectRoot.toString());
        assertThat(out).containsEntry("responseMode", "references");
        var bucket = (Map<String, Object>) out.get("rules");
        // Same project on both sides -> every rule is common, none divergent.
        var common = (List<Map<String, Object>>) bucket.get("common");
        assertThat(common).isNotEmpty();
        assertThat(common.get(0)).containsKeys("id", "contentHash", "ref", "path", "anchor");
        assertThat(common.get(0)).doesNotContainKeys("description", "rationale", "items", "steps");
    }

    /** Write a minimal valid standards pack (one rule, skill, checklist) under the project. */
    private void writeStandardsPack() throws IOException {
        var dir = projectRoot.resolve(".launchpad/standards");
        Files.createDirectories(dir.resolve("rules"));
        Files.writeString(dir.resolve("standards-pack.yml"), """
            schemaVersion: 1
            id: acme-pack
            version: 1.0.0
            includes:
              rules:
                - rules/rules.yml
              skills:
                - rules/skills.yml
              checklists:
                - rules/checklists.yml
            """);
        Files.writeString(dir.resolve("rules/rules.yml"), """
            version: 1
            rules:
              - id: java.no-field-injection
                title: No field injection
                severity: HIGH
                content: Use constructor injection. It keeps dependencies explicit.
                rationale: Field injection hides dependencies.
                priority: 10
            """);
        Files.writeString(dir.resolve("rules/skills.yml"), """
            version: 1
            skills:
              - id: skill.add-endpoint
                title: Add an endpoint
                trigger: When adding a REST endpoint
                steps:
                  - Write the controller
                  - Add a test
            """);
        Files.writeString(dir.resolve("rules/checklists.yml"), """
            version: 1
            checklists:
              - id: check.pr
                title: PR checklist
                items:
                  - id: ci-green
                    text: CI is green
                    required: true
            """);
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

    @Test
    @SuppressWarnings("unchecked")
    void getStandardsByRuleIdReturnsSingleRuleReference() throws IOException {
        writeStandardsPack();

        var out = tools.getStandards(projectRoot.toString(), "java.no-field-injection");
        assertThat(out).doesNotContainKeys("rules", "skills", "checklists");
        assertThat(out).containsEntry("responseMode", "references");
        var rule = (Map<String, Object>) out.get("rule");
        assertThat(rule).containsEntry("id", "java.no-field-injection");
        assertThat(rule).containsKeys("contentHash", "summary", "ref", "path", "anchor");
        assertThat(rule).doesNotContainKeys("description", "rationale");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStandardsByRuleIdInlineReturnsFullBody() throws IOException {
        writeStandardsPack();

        var out = toolsWith("inline").getStandards(projectRoot.toString(), "java.no-field-injection");
        assertThat(out).containsOnlyKeys("rule");
        var rule = (Map<String, Object>) out.get("rule");
        assertThat(rule).containsKeys("id", "title", "severity", "description", "rationale");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStandardsUnknownRuleIdListsAvailableIds() throws IOException {
        writeStandardsPack();

        var out = tools.getStandards(projectRoot.toString(), "does.not.exist");
        var err = errorEnvelope(out);
        assertThat(err).containsEntry("code", "unknown_rule_id");
        assertThat(details(err)).containsEntry("requested", "does.not.exist");
        assertThat((List<String>) details(err).get("availableRuleIds"))
            .contains("java.no-field-injection");
    }

    @Test
    void lintStandardsStubReportsTrackingIssue() {
        var out = tools.lintStandards("some/path");
        var err = errorEnvelope(out);
        assertThat(err).containsEntry("code", "not_yet_available");
        assertThat(err).containsEntry("type", "UNSUPPORTED");
        assertThat(details(err)).containsEntry("trackingIssue", 16);
    }

    @Test
    void auditFindingsDiffStubReportsTrackingIssue() {
        var out = tools.auditFindingsDiff(projectRoot.toString(), null);
        var err = errorEnvelope(out);
        assertThat(err).containsEntry("code", "not_yet_available");
        assertThat(details(err)).containsEntry("trackingIssue", 21);
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
