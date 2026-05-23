package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Extracts dependencies from full build-file content (not first 60 lines).
 * Returns name+version+scope when derivable. Output is deduped while
 * preserving declaration order.
 */
public final class DependencyExtractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    public List<Dependency> extract(Map<String, String> keyFileContents) {
        var deps = new LinkedHashMap<String, Dependency>();
        keyFileContents.forEach((path, content) -> {
            var name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            switch (name) {
                case "pom.xml" -> parsePom(content).forEach(d -> deps.putIfAbsent(key(d), d));
                case "package.json" -> parsePackageJson(content).forEach(d -> deps.putIfAbsent(key(d), d));
                case "go.mod" -> parseGoMod(content).forEach(d -> deps.putIfAbsent(key(d), d));
                case "Cargo.toml" -> parseCargoToml(content).forEach(d -> deps.putIfAbsent(key(d), d));
                case "requirements.txt" -> parseRequirementsTxt(content).forEach(d -> deps.putIfAbsent(key(d), d));
                case "pyproject.toml" -> parsePyproject(content).forEach(d -> deps.putIfAbsent(key(d), d));
                case "Gemfile" -> parseGemfile(content).forEach(d -> deps.putIfAbsent(key(d), d));
                default -> {
                    if (name.endsWith(".tf")) {
                        parseTerraformProviders(content).forEach(d -> deps.putIfAbsent(key(d), d));
                    }
                }
            }
        });
        return new ArrayList<>(deps.values());
    }

    private static String key(Dependency d) {
        return d.name() + "@" + (d.scope() == null ? "" : d.scope());
    }

    // === pom.xml ===

    private static List<Dependency> parsePom(String pom) {
        var out = new ArrayList<Dependency>();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            var doc = builder.parse(new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)));
            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                var el = (Element) depNodes.item(i);
                String groupId = textOf(el, "groupId");
                String artifactId = textOf(el, "artifactId");
                String version = textOf(el, "version");
                String scope = textOf(el, "scope");
                if (artifactId == null || artifactId.isBlank()) continue;
                String name = groupId == null || groupId.isBlank() ? artifactId : groupId + ":" + artifactId;
                out.add(new Dependency(name, version, scope == null ? "runtime" : scope));
            }
        } catch (Exception ignored) {
            // pom unparseable - return what we have
        }
        return out;
    }

    private static String textOf(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getParentNode() == parent) {
                String t = n.getTextContent();
                return t == null ? null : t.strip();
            }
        }
        return null;
    }

    // === package.json ===

    private static List<Dependency> parsePackageJson(String json) {
        var out = new ArrayList<Dependency>();
        try {
            JsonNode root = JSON.readTree(json);
            collectNodeDeps(root.path("dependencies"), "runtime", out);
            collectNodeDeps(root.path("devDependencies"), "dev", out);
            collectNodeDeps(root.path("peerDependencies"), "peer", out);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void collectNodeDeps(JsonNode node, String scope, List<Dependency> out) {
        if (node == null || !node.isObject()) return;
        node.properties().forEach(entry ->
            out.add(new Dependency(entry.getKey(), entry.getValue().asText(""), scope)));
    }

    // === go.mod ===

    private static List<Dependency> parseGoMod(String content) {
        var out = new ArrayList<Dependency>();
        boolean inBlock = false;
        for (String line : content.split("\\R")) {
            var s = line.strip();
            if (s.startsWith("require (")) { inBlock = true; continue; }
            if (inBlock && s.equals(")")) { inBlock = false; continue; }
            String spec;
            if (inBlock) spec = s;
            else if (s.startsWith("require ")) spec = s.substring("require ".length()).strip();
            else continue;
            spec = stripComment(spec);
            if (spec.isEmpty()) continue;
            var parts = spec.split("\\s+");
            if (parts.length >= 2) out.add(new Dependency(parts[0], parts[1], "runtime"));
        }
        return out;
    }

    // === Cargo.toml ===

    private static List<Dependency> parseCargoToml(String content) {
        var out = new ArrayList<Dependency>();
        String section = null;
        for (String raw : content.split("\\R")) {
            var line = stripComment(raw).strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) { section = line; continue; }
            if (section == null) continue;
            String scope;
            if (section.equals("[dependencies]")) scope = "runtime";
            else if (section.equals("[dev-dependencies]")) scope = "dev";
            else if (section.equals("[build-dependencies]")) scope = "build";
            else continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).strip();
            String rest = line.substring(eq + 1).strip();
            String version = rest.startsWith("\"")
                ? rest.substring(1, Math.max(1, rest.indexOf('"', 1)))
                : extractInlineVersion(rest);
            out.add(new Dependency(name, version, scope));
        }
        return out;
    }

    private static String extractInlineVersion(String rest) {
        int v = rest.indexOf("version");
        if (v < 0) return "";
        int q1 = rest.indexOf('"', v);
        if (q1 < 0) return "";
        int q2 = rest.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        return rest.substring(q1 + 1, q2);
    }

    // === requirements.txt ===

    private static List<Dependency> parseRequirementsTxt(String content) {
        var out = new ArrayList<Dependency>();
        for (String raw : content.split("\\R")) {
            var line = stripComment(raw).strip();
            if (line.isEmpty() || line.startsWith("-")) continue;
            // strip extras: pkg[extras]==1.0
            int bracket = line.indexOf('[');
            String head = bracket > 0 ? line.substring(0, bracket) + line.substring(line.indexOf(']') + 1) : line;
            String name;
            String version = "";
            int sep = -1;
            for (String s : new String[] { "==", ">=", "<=", "~=", ">", "<" }) {
                int idx = head.indexOf(s);
                if (idx > 0 && (sep < 0 || idx < sep)) {
                    sep = idx;
                    name = head.substring(0, idx).strip();
                    version = head.substring(idx).strip();
                    out.add(new Dependency(name, version, "runtime"));
                    break;
                }
            }
            if (sep < 0) {
                out.add(new Dependency(head.strip(), "", "runtime"));
            }
        }
        return out;
    }

    // === pyproject.toml ===

    private static List<Dependency> parsePyproject(String content) {
        var out = new ArrayList<Dependency>();
        String section = null;
        boolean inDependenciesArray = false;
        for (String raw : content.split("\\R")) {
            var line = stripComment(raw).strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) {
                section = line;
                inDependenciesArray = false;
                continue;
            }
            // PEP 621 [project] dependencies = [ "pkg>=1", ... ]
            if ("[project]".equals(section) && line.startsWith("dependencies")) {
                inDependenciesArray = line.contains("[") && !line.contains("]");
                int b1 = line.indexOf('[');
                if (b1 >= 0) {
                    String inner = line.substring(b1 + 1, line.contains("]") ? line.indexOf(']') : line.length());
                    addPepDeps(inner, out);
                }
                continue;
            }
            if (inDependenciesArray) {
                if (line.contains("]")) {
                    inDependenciesArray = false;
                    line = line.substring(0, line.indexOf(']'));
                }
                addPepDeps(line, out);
                continue;
            }
            // Poetry [tool.poetry.dependencies] python = "^3.10" \n requests = "^2.28"
            if (section != null && section.startsWith("[tool.poetry") && section.contains("dependencies]")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String name = line.substring(0, eq).strip();
                if ("python".equals(name)) continue;
                String rest = line.substring(eq + 1).strip();
                String version = rest.startsWith("\"")
                    ? rest.substring(1, Math.max(1, rest.indexOf('"', 1)))
                    : extractInlineVersion(rest);
                String scope = section.contains("dev") || section.contains("test") ? "dev" : "runtime";
                out.add(new Dependency(name, version, scope));
            }
        }
        return out;
    }

    private static void addPepDeps(String snippet, List<Dependency> out) {
        for (String piece : snippet.split(",")) {
            var s = piece.strip();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1);
            }
            if (s.isEmpty()) continue;
            String[] split = s.split("[<>=~!]", 2);
            String name = split[0].strip().replaceAll("\\[.*]", "");
            String version = split.length > 1 ? s.substring(split[0].length()).strip() : "";
            out.add(new Dependency(name, version, "runtime"));
        }
    }

    // === Gemfile ===

    private static List<Dependency> parseGemfile(String content) {
        var out = new ArrayList<Dependency>();
        for (String raw : content.split("\\R")) {
            var line = stripComment(raw).strip();
            if (!line.startsWith("gem ")) continue;
            // gem "rails", "~> 7.0"
            int q1 = line.indexOf('"'); if (q1 < 0) q1 = line.indexOf('\'');
            if (q1 < 0) continue;
            char quote = line.charAt(q1);
            int q2 = line.indexOf(quote, q1 + 1);
            if (q2 < 0) continue;
            String name = line.substring(q1 + 1, q2);
            String version = "";
            int q3 = line.indexOf(quote, q2 + 1);
            int q4 = q3 > 0 ? line.indexOf(quote, q3 + 1) : -1;
            if (q3 > 0 && q4 > 0) version = line.substring(q3 + 1, q4);
            out.add(new Dependency(name, version, "runtime"));
        }
        return out;
    }

    private static String stripComment(String line) {
        int h = line.indexOf('#');
        return h < 0 ? line : line.substring(0, h);
    }

    // === Terraform (*.tf) ===

    private static final java.util.regex.Pattern TF_REQUIRED_PROVIDERS_BLOCK =
        java.util.regex.Pattern.compile("required_providers\\s*\\{([^{}]*?(?:\\{[^{}]*?\\}[^{}]*?)*)\\}",
            java.util.regex.Pattern.DOTALL);

    private static final java.util.regex.Pattern TF_PROVIDER_ENTRY =
        java.util.regex.Pattern.compile(
            "([A-Za-z0-9_-]+)\\s*=\\s*\\{([^{}]*?)\\}",
            java.util.regex.Pattern.DOTALL);

    private static final java.util.regex.Pattern TF_SOURCE_ATTR =
        java.util.regex.Pattern.compile("source\\s*=\\s*\"([^\"]+)\"");

    private static final java.util.regex.Pattern TF_VERSION_ATTR =
        java.util.regex.Pattern.compile("version\\s*=\\s*\"([^\"]+)\"");

    /**
     * Parses {@code terraform { required_providers { ... } }} blocks from
     * a single .tf file. Regex-based; works for the vast majority of real-world
     * versions.tf shapes (one block per file, providers as map entries).
     * Skipped entirely when the file has no required_providers block.
     */
    private static List<Dependency> parseTerraformProviders(String tfContent) {
        var out = new ArrayList<Dependency>();
        var blockMatcher = TF_REQUIRED_PROVIDERS_BLOCK.matcher(tfContent);
        while (blockMatcher.find()) {
            String inner = blockMatcher.group(1);
            var entryMatcher = TF_PROVIDER_ENTRY.matcher(inner);
            while (entryMatcher.find()) {
                String body = entryMatcher.group(2);
                var src = TF_SOURCE_ATTR.matcher(body);
                if (!src.find()) continue;
                String source = src.group(1);   // e.g. "databricks/databricks"
                String version = "";
                var ver = TF_VERSION_ATTR.matcher(body);
                if (ver.find()) version = ver.group(1);
                out.add(new Dependency(source, version, "provider"));
            }
        }
        return out;
    }
}
