package com.acltabontabon.launchpad.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.Check;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SarifWriterTest {

    private final SarifWriter writer = new SarifWriter();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void writesValidSarifWithSchemaAndResults(@TempDir Path root) throws Exception {
        var rule = new Rule("no-field-injection", "Constructor Injection", "must",
            "desc", "rationale", Scope.empty(), 1,
            new Check("forbid-pattern", "@Autowired", List.of(), List.of(), List.of(), List.of(), null, null));
        var finding = new Finding("no-field-injection", "must", "Constructor Injection",
            "src/Foo.java", 7, "Forbidden pattern matched.", "@Autowired");

        var path = writer.write(root, List.of(rule), List.of(finding));
        var doc = json.readTree(path.toFile());

        assertThat(doc.get("version").asText()).isEqualTo("2.1.0");
        assertThat(doc.get("$schema").asText()).contains("sarif");
        JsonNode result = doc.get("runs").get(0).get("results").get(0);
        assertThat(result.get("ruleId").asText()).isEqualTo("no-field-injection");
        assertThat(result.get("level").asText()).isEqualTo("error");
        assertThat(result.get("locations").get(0)
            .get("physicalLocation").get("artifactLocation").get("uri").asText())
            .isEqualTo("src/Foo.java");
        assertThat(result.get("locations").get(0)
            .get("physicalLocation").get("region").get("startLine").asInt()).isEqualTo(7);
    }

    @Test
    void mapsSeverityToSarifLevel() {
        assertThat(SarifWriter.sarifLevel("never")).isEqualTo("error");
        assertThat(SarifWriter.sarifLevel("must")).isEqualTo("error");
        assertThat(SarifWriter.sarifLevel("should")).isEqualTo("warning");
        assertThat(SarifWriter.sarifLevel("avoid")).isEqualTo("note");
        assertThat(SarifWriter.sarifLevel(null)).isEqualTo("warning");
    }
}
