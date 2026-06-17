package com.acltabontabon.launchpad.standards.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Check;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.ResolvedStandards;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import com.acltabontabon.launchpad.standards.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic-generation assertions for the standards-index sidecar. The
 * assembler is pure (resolved pack in, index out), so these run without fixtures
 * or a model and are part of {@code ./mvnw test}.
 */
class StandardsIndexAssemblerTest {

    private static final String FIXED_TS = "2026-01-01T00:00:00Z";
    private static final StandardsSource SOURCE =
        new StandardsSource("acme-pack", "1.2.0", StandardsSource.ORIGIN_LOCAL_OVERRIDE);

    private final StandardsIndexAssembler assembler = new StandardsIndexAssembler();

    private static ResolvedStandards bundle(List<Rule> rules, StandardsSource source) {
        return new ResolvedStandards(rules, List.of(), List.of(), source);
    }

    private static Rule auditableRule() {
        return new Rule(
            "java.no-field-injection", "No field injection", "HIGH",
            "Use constructor injection.", "Field injection hides dependencies.",
            new Scope(List.of("java"), List.of("spring-boot"), List.of(), List.of(), List.of()),
            10,
            new Check("forbid-pattern", "@Autowired", List.of(), List.of(), List.of(), List.of(), null, null));
    }

    private static Rule docOnlyRule() {
        return new Rule(
            "doc.readme", "Keep README current", "LOW",
            "Update the README with every feature.", "Docs drift otherwise.",
            Scope.empty(), 5, null);
    }

    private static Skill sampleSkill() {
        return new Skill("skill.add-endpoint", "Add an endpoint", "When adding a REST endpoint",
            List.of("Write controller", "Add test"), List.of("Tests pass"), "notes", Scope.empty());
    }

    private static Checklist sampleChecklist() {
        return new Checklist("check.pr", "PR checklist",
            List.of(new ChecklistItem("ci-green", "CI is green", true),
                new ChecklistItem("changelog", "CHANGELOG updated", false)),
            Scope.empty());
    }

    @Test
    void ordersByPriorityThenId() {
        var index = assembler.assemble(bundle(List.of(auditableRule(), docOnlyRule()), SOURCE), FIXED_TS);

        // docOnlyRule has priority 5, auditableRule 10 -> doc first.
        assertThat(index.rules()).extracting(StandardsRuleEntry::id)
            .containsExactly("doc.readme", "java.no-field-injection");
        assertThat(index.rules()).extracting(StandardsRuleEntry::id).doesNotHaveDuplicates();
        assertThat(index.schemaVersion()).isEqualTo(StandardsIndex.SCHEMA_VERSION);
        assertThat(index.source()).isEqualTo(SOURCE);
    }

    @Test
    void reproducibleForIdenticalTimestamp() {
        var pack = new ResolvedStandards(
            List.of(auditableRule(), docOnlyRule()), List.of(sampleSkill()), List.of(sampleChecklist()), SOURCE);
        assertThat(assembler.assemble(pack, FIXED_TS))
            .isEqualTo(assembler.assemble(pack, FIXED_TS));
    }

    @Test
    void generatedAtNeverChangesRecordContent() {
        var pack = new ResolvedStandards(
            List.of(auditableRule(), docOnlyRule()), List.of(sampleSkill()), List.of(sampleChecklist()), SOURCE);
        var a = assembler.assemble(pack, "2020-01-01T00:00:00Z");
        var b = assembler.assemble(pack, "2099-12-31T23:59:59Z");

        // Only the top-level timestamp differs; every record and contentHash is identical.
        assertThat(a.generatedAt()).isNotEqualTo(b.generatedAt());
        assertThat(a.rules()).isEqualTo(b.rules());
        assertThat(a.skills()).isEqualTo(b.skills());
        assertThat(a.checklists()).isEqualTo(b.checklists());
    }

    @Test
    void populatesAuditMetadataPerRule() {
        var index = assembler.assemble(bundle(List.of(auditableRule(), docOnlyRule()), SOURCE), FIXED_TS);

        var auditable = index.rules().stream().filter(r -> r.id().equals("java.no-field-injection")).findFirst().orElseThrow();
        assertThat(auditable.auditable()).isTrue();
        assertThat(auditable.checkKind()).isEqualTo("forbid-pattern");

        var docOnly = index.rules().stream().filter(r -> r.id().equals("doc.readme")).findFirst().orElseThrow();
        assertThat(docOnly.auditable()).isFalse();
        assertThat(docOnly.checkKind()).isNull();
    }

    @Test
    void projectsSkillsAndChecklistsWithHashes() {
        var pack = new ResolvedStandards(
            List.of(auditableRule()), List.of(sampleSkill()), List.of(sampleChecklist()), SOURCE);
        var index = assembler.assemble(pack, FIXED_TS);

        assertThat(index.skills()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("skill.add-endpoint");
            assertThat(s.steps()).containsExactly("Write controller", "Add test");
            assertThat(s.source()).isEqualTo(SOURCE);
            assertThat(s.contentHash()).isNotBlank();
        });
        assertThat(index.checklists()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("check.pr");
            assertThat(c.items()).extracting(ChecklistItem::id).containsExactly("ci-green", "changelog");
            assertThat(c.source()).isEqualTo(SOURCE);
            assertThat(c.contentHash()).isNotBlank();
        });
    }

    @Test
    void ordersSkillsAndChecklistsById() {
        var pack = new ResolvedStandards(
            List.of(),
            List.of(new Skill("z-skill", "Z", null, null, null, null, Scope.empty()),
                new Skill("a-skill", "A", null, null, null, null, Scope.empty())),
            List.of(new Checklist("z-check", "Z", List.of(), Scope.empty()),
                new Checklist("a-check", "A", List.of(), Scope.empty())),
            SOURCE);
        var index = assembler.assemble(pack, FIXED_TS);

        assertThat(index.skills()).extracting(StandardsSkillEntry::id).containsExactly("a-skill", "z-skill");
        assertThat(index.checklists()).extracting(StandardsChecklistEntry::id).containsExactly("a-check", "z-check");
    }

    @Test
    void eachEntryIsSelfSufficient() {
        var index = assembler.assemble(bundle(List.of(auditableRule()), SOURCE), FIXED_TS);

        assertThat(index.rules()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isNotBlank();
            assertThat(entry.title()).isNotBlank();
            assertThat(entry.severity()).isNotBlank();
            assertThat(entry.scope()).isNotNull();
            assertThat(entry.description()).contains("constructor injection");
            assertThat(entry.rationale()).isNotBlank();
            assertThat(entry.source()).isEqualTo(SOURCE);
            assertThat(entry.priority()).isEqualTo(10);
            assertThat(entry.contentHash()).isNotBlank();
        });
    }

    @Test
    void emptyPackYieldsEmptyIndexWithNullSource() {
        var index = assembler.assemble(bundle(List.of(), null), FIXED_TS);

        assertThat(index.rules()).isEmpty();
        assertThat(index.skills()).isEmpty();
        assertThat(index.checklists()).isEmpty();
        assertThat(index.source()).isNull();
        assertThat(index.schemaVersion()).isEqualTo(StandardsIndex.SCHEMA_VERSION);
    }

    @Test
    void survivesJsonRoundTrip() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.INDENT_OUTPUT);
        var pack = new ResolvedStandards(
            List.of(auditableRule(), docOnlyRule()), List.of(sampleSkill()), List.of(sampleChecklist()), SOURCE);
        var index = assembler.assemble(pack, FIXED_TS);

        var serialized = json.writeValueAsString(index);
        var restored = json.readValue(serialized, StandardsIndex.class);

        assertThat(restored).isEqualTo(index);
        assertThat(serialized).contains("java.no-field-injection").contains("\"schemaVersion\"");
    }
}
