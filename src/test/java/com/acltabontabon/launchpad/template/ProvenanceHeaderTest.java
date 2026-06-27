package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.standards.index.StandardsSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProvenanceHeaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void rendersSingleLineMarkerCommentWithJsonPayload() throws Exception {
        var source = new StandardsSource("acme-standards", "1.2.0",
            StandardsSource.ORIGIN_REMOTE_CACHE);
        var rendered = ProvenanceHeader.of("2026-06-22T14:30:45Z", source, "qwen2.5-coder").render();

        // Single physical line, terminated by exactly one newline.
        assertThat(rendered).endsWith("-->\n");
        assertThat(rendered.stripTrailing()).doesNotContain("\n");
        assertThat(rendered).startsWith("<!-- " + ProvenanceHeader.MARKER + " {");

        var node = parsePayload(rendered);
        assertThat(node.get("schemaVersion").asInt()).isEqualTo(ProvenanceHeader.SCHEMA_VERSION);
        assertThat(node.get("launchpadVersion").asText()).isNotBlank();
        assertThat(node.get("generatedAt").asText()).isEqualTo("2026-06-22T14:30:45Z");
        assertThat(node.get("aiModel").asText()).isEqualTo("qwen2.5-coder");
        assertThat(node.get("standards").get("pack").asText()).isEqualTo("acme-standards");
        assertThat(node.get("standards").get("version").asText()).isEqualTo("1.2.0");
        assertThat(node.get("standards").get("origin").asText())
            .isEqualTo(StandardsSource.ORIGIN_REMOTE_CACHE);
    }

    @Test
    void nullStandardsSourceSerializesAsJsonNull() throws Exception {
        var rendered = ProvenanceHeader.of("2026-06-22T14:30:45Z", null,
            ProvenanceHeader.DETERMINISTIC_ONLY).render();

        var node = parsePayload(rendered);
        assertThat(node.get("standards").isNull()).isTrue();
        assertThat(node.get("aiModel").asText()).isEqualTo(ProvenanceHeader.DETERMINISTIC_ONLY);
    }

    @Test
    void parseRecoversTheHeaderFromRenderedContent() {
        var source = new StandardsSource("acme-standards", "1.2.0",
            StandardsSource.ORIGIN_REMOTE_CACHE);
        var original = ProvenanceHeader.of("2026-06-22T14:30:45Z", source, "qwen2.5-coder");
        var fileContent = original.render() + "# Project\n\nBody.\n";

        var parsed = ProvenanceHeader.parse(fileContent);

        assertThat(parsed).isPresent();
        assertThat(parsed.get()).isEqualTo(original);
    }

    @Test
    void parseReturnsEmptyWhenMarkerAbsent() {
        assertThat(ProvenanceHeader.parse("# Project\n\nNo stamp here.\n")).isEmpty();
    }

    @Test
    void parseReturnsEmptyOnMalformedPayload() {
        assertThat(ProvenanceHeader.parse("<!-- " + ProvenanceHeader.MARKER + " {not json} -->\n"))
            .isEmpty();
        assertThat(ProvenanceHeader.parse(null)).isEmpty();
    }

    private static JsonNode parsePayload(String rendered) throws Exception {
        var prefix = "<!-- " + ProvenanceHeader.MARKER + " ";
        var json = rendered.stripTrailing();
        json = json.substring(prefix.length(), json.length() - " -->".length());
        return JSON.readTree(json);
    }
}
