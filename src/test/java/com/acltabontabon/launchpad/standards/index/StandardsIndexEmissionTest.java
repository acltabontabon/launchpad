package com.acltabontabon.launchpad.standards.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acltabontabon.launchpad.standards.RemoteStandardsFetcher;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end check of the exact sequence the runner performs:
 * {@code loadResolvedStandards -> assemble -> save}. Validates that the persisted
 * file matches the documented shape, including {@code @JsonInclude(NON_NULL)}
 * omissions and the empty-state contract.
 */
class StandardsIndexEmissionTest {

    private final StandardsIndexAssembler assembler = new StandardsIndexAssembler();
    private final StandardsIndexStore store = new StandardsIndexStore();

    private void emit(Path projectRoot, StandardsLoader loader) {
        var resolved = loader.loadResolvedStandards(projectRoot);
        var index = assembler.assemble(resolved, "2026-06-17T00:00:00Z");
        store.save(projectRoot, index);
    }

    @Test
    void emitsSidecarWithRecordsForAResolvedPack(@TempDir Path projectRoot) throws Exception {
        var dir = projectRoot.resolve(".launchpad/standards");
        Files.createDirectories(dir.resolve("rules"));
        Files.writeString(dir.resolve("standards-pack.yml"), """
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
                content: Use constructor injection.
                rationale: Field injection hides dependencies.
                priority: 10
                check:
                  kind: forbid-pattern
                  pattern: "@Autowired"
              - id: doc.readme
                title: Keep README current
                severity: LOW
                content: Update the README with every feature.
                priority: 5
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

        emit(projectRoot, new StandardsLoader(noRemote(), null));

        var json = Files.readString(projectRoot.resolve(".launchpad/standards.index.json"));
        assertThat(json).contains("\"schemaVersion\" : 2");          // pretty-printed
        assertThat(json).contains("java.no-field-injection");
        assertThat(json).contains("\"checkKind\" : \"forbid-pattern\"");
        assertThat(json).contains("\"origin\" : \"local-override\"");
        assertThat(json).contains("skill.add-endpoint");
        assertThat(json).contains("check.pr");
        // Doc-only rule: checkKind is null and must be omitted (NON_NULL).
        assertThat(json).doesNotContain("\"checkKind\" : null");

        var reloaded = store.load(projectRoot).orElseThrow();
        assertThat(reloaded.rules()).extracting(StandardsRuleEntry::id)
            .containsExactly("doc.readme", "java.no-field-injection");   // priority order
        assertThat(reloaded.skills()).extracting(StandardsSkillEntry::id).containsExactly("skill.add-endpoint");
        assertThat(reloaded.checklists()).extracting(StandardsChecklistEntry::id).containsExactly("check.pr");
        assertThat(reloaded.rules()).allSatisfy(r -> assertThat(r.contentHash()).isNotBlank());
        assertThat(reloaded.skills()).allSatisfy(s -> assertThat(s.contentHash()).isNotBlank());
        assertThat(reloaded.checklists()).allSatisfy(c -> assertThat(c.contentHash()).isNotBlank());
        assertThat(reloaded.source().origin()).isEqualTo(StandardsSource.ORIGIN_LOCAL_OVERRIDE);
    }

    @Test
    void emitsEmptyStateSidecarWhenNoStandards(@TempDir Path projectRoot) {
        emit(projectRoot, new StandardsLoader(noRemote(), null));

        var reloaded = store.load(projectRoot).orElseThrow();
        assertThat(reloaded.rules()).isEmpty();
        assertThat(reloaded.skills()).isEmpty();
        assertThat(reloaded.checklists()).isEmpty();
        assertThat(reloaded.source()).isNull();
        assertThat(reloaded.schemaVersion()).isEqualTo(StandardsIndex.SCHEMA_VERSION);
    }

    private static RemoteStandardsFetcher noRemote() {
        var m = mock(RemoteStandardsFetcher.class);
        when(m.ensureCache()).thenReturn(Optional.empty());
        return m;
    }
}
