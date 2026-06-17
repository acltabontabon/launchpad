package com.acltabontabon.launchpad.standards.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Check;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic-generation assertions for the standards-index sidecar. The
 * assembler is pure (rules + source in, index out), so these run without
 * fixtures or a model and are part of {@code ./mvnw test}.
 */
class StandardsIndexAssemblerTest {

    private static final String FIXED_TS = "2026-01-01T00:00:00Z";
    private static final StandardsSource SOURCE =
        new StandardsSource("acme-pack", "1.2.0", StandardsSource.ORIGIN_LOCAL_OVERRIDE);

    private final StandardsIndexAssembler assembler = new StandardsIndexAssembler();

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

    @Test
    void ordersByPriorityThenId() {
        var index = assembler.assemble(List.of(auditableRule(), docOnlyRule()), SOURCE, FIXED_TS);

        // docOnlyRule has priority 5, auditableRule 10 -> doc first.
        assertThat(index.rules()).extracting(StandardsRuleEntry::id)
            .containsExactly("doc.readme", "java.no-field-injection");
        assertThat(index.rules()).extracting(StandardsRuleEntry::id).doesNotHaveDuplicates();
        assertThat(index.schemaVersion()).isEqualTo(StandardsIndex.SCHEMA_VERSION);
        assertThat(index.source()).isEqualTo(SOURCE);
    }

    @Test
    void reproducibleForIdenticalTimestamp() {
        var rules = List.of(auditableRule(), docOnlyRule());
        assertThat(assembler.assemble(rules, SOURCE, FIXED_TS))
            .isEqualTo(assembler.assemble(rules, SOURCE, FIXED_TS));
    }

    @Test
    void generatedAtNeverChangesRuleContent() {
        var rules = List.of(auditableRule(), docOnlyRule());
        var a = assembler.assemble(rules, SOURCE, "2020-01-01T00:00:00Z");
        var b = assembler.assemble(rules, SOURCE, "2099-12-31T23:59:59Z");

        // Only the top-level timestamp differs; rule order, fields, and every
        // contentHash are identical.
        assertThat(a.generatedAt()).isNotEqualTo(b.generatedAt());
        assertThat(a.rules()).isEqualTo(b.rules());
    }

    @Test
    void populatesAuditMetadataPerRule() {
        var index = assembler.assemble(List.of(auditableRule(), docOnlyRule()), SOURCE, FIXED_TS);

        var auditable = index.rules().stream().filter(r -> r.id().equals("java.no-field-injection")).findFirst().orElseThrow();
        assertThat(auditable.auditable()).isTrue();
        assertThat(auditable.checkKind()).isEqualTo("forbid-pattern");

        var docOnly = index.rules().stream().filter(r -> r.id().equals("doc.readme")).findFirst().orElseThrow();
        assertThat(docOnly.auditable()).isFalse();
        assertThat(docOnly.checkKind()).isNull();
    }

    @Test
    void eachEntryIsSelfSufficient() {
        var index = assembler.assemble(List.of(auditableRule()), SOURCE, FIXED_TS);

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
    void emptyRulesYieldEmptyIndexWithNullSource() {
        var index = assembler.assemble(List.of(), null, FIXED_TS);

        assertThat(index.rules()).isEmpty();
        assertThat(index.source()).isNull();
        assertThat(index.schemaVersion()).isEqualTo(StandardsIndex.SCHEMA_VERSION);
    }

    @Test
    void survivesJsonRoundTrip() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.INDENT_OUTPUT);
        var index = assembler.assemble(List.of(auditableRule(), docOnlyRule()), SOURCE, FIXED_TS);

        var serialized = json.writeValueAsString(index);
        var restored = json.readValue(serialized, StandardsIndex.class);

        assertThat(restored).isEqualTo(index);
        assertThat(serialized).contains("java.no-field-injection").contains("\"schemaVersion\"");
    }
}
