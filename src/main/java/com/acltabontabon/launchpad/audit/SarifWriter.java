package com.acltabontabon.launchpad.audit;

import com.acltabontabon.launchpad.standards.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Writes audit findings as a SARIF 2.1.0 document (Static Analysis Results
 * Interchange Format). SARIF is the OASIS standard that GitHub code-scanning,
 * VS Code SARIF Viewer, IntelliJ Qodana, and most static analysis tools consume,
 * so emitting it means clients get inline-highlighted findings for free.
 * <p>
 * The schema is just nested JSON - no external library required.
 */
@Component
public class SarifWriter {

    private static final String SARIF_VERSION = "2.1.0";
    private static final String SARIF_SCHEMA = "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json";

    private final ObjectMapper json = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    public Path write(Path projectRoot, List<Rule> auditedRules, List<Finding> findings,
                      Map<String, String> ruleHashById) {
        var target = projectRoot.resolve(".launchpad").resolve("audit.sarif.json");
        try {
            Files.createDirectories(target.getParent());
            json.writeValue(target.toFile(), buildDocument(auditedRules, findings, ruleHashById));
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }

    private Map<String, Object> buildDocument(List<Rule> auditedRules, List<Finding> findings,
                                              Map<String, String> ruleHashById) {
        var doc = new LinkedHashMap<String, Object>();
        doc.put("$schema", SARIF_SCHEMA);
        doc.put("version", SARIF_VERSION);
        doc.put("runs", List.of(buildRun(auditedRules, findings, ruleHashById)));
        return doc;
    }

    private Map<String, Object> buildRun(List<Rule> auditedRules, List<Finding> findings,
                                         Map<String, String> ruleHashById) {
        var driver = new LinkedHashMap<String, Object>();
        driver.put("name", "Launchpad");
        driver.put("informationUri", "https://github.com/acltabontabon/Launchpad");
        driver.put("rules", auditedRules.stream().map(r -> ruleDescriptor(r, ruleHashById)).toList());

        var tool = Map.of("driver", driver);

        var results = new ArrayList<Map<String, Object>>(findings.size());
        for (var f : findings) {
            results.add(resultFor(f));
        }
        var run = new LinkedHashMap<String, Object>();
        run.put("tool", tool);
        run.put("results", results);
        return run;
    }

    private Map<String, Object> ruleDescriptor(Rule rule, Map<String, String> ruleHashById) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", rule.id());
        out.put("name", rule.title() == null ? rule.id() : rule.title());
        if (rule.description() != null) {
            out.put("shortDescription", Map.of("text", rule.description()));
        }
        if (rule.rationale() != null) {
            out.put("fullDescription", Map.of("text", rule.rationale()));
        }
        out.put("defaultConfiguration", Map.of("level", sarifLevel(rule.severity())));
        String ruleHash = ruleHashById == null ? null : ruleHashById.get(rule.id());
        if (ruleHash != null) {
            out.put("properties", Map.of("ruleHash", ruleHash));
        }
        return out;
    }

    private Map<String, Object> resultFor(Finding f) {
        var result = new LinkedHashMap<String, Object>();
        result.put("ruleId", f.ruleId());
        result.put("level", sarifLevel(f.severity()));
        result.put("message", Map.of("text", f.message() + (f.evidence() == null ? "" : "  // " + f.evidence())));
        if (f.ruleHash() != null) {
            result.put("properties", Map.of("ruleHash", f.ruleHash()));
        }
        if (f.filePath() != null) {
            var region = new LinkedHashMap<String, Object>();
            if (f.line() != null) region.put("startLine", f.line());
            var location = Map.of(
                "physicalLocation", Map.of(
                    "artifactLocation", Map.of("uri", f.filePath()),
                    "region", region
                )
            );
            result.put("locations", List.of(location));
        }
        return result;
    }

    static String sarifLevel(String severity) {
        if (severity == null) return "warning";
        return switch (severity.toLowerCase()) {
            case "never", "must" -> "error";
            case "should" -> "warning";
            case "avoid" -> "note";
            default -> "warning";
        };
    }
}
