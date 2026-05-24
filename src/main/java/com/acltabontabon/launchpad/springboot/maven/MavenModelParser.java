package com.acltabontabon.launchpad.springboot.maven;

import com.acltabontabon.launchpad.scanner.Dependency;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses a {@code pom.xml} into a structured {@link MavenModel} - parent
 * coordinates, dependencies, build plugins. The single deterministic Maven
 * entry point used by dependency extraction and project-support detection.
 *
 * <p>Doctype declarations are disabled; namespaces are ignored so the parser
 * accepts both default-namespaced and bare poms. Unparseable input returns
 * {@link MavenModel#empty()} rather than throwing - downstream callers decide
 * what to do with an empty model.
 */
public final class MavenModelParser {

    public MavenModel parse(String pomXml) {
        if (pomXml == null || pomXml.isBlank()) return MavenModel.empty();
        Element root = parseRoot(pomXml);
        if (root == null) return MavenModel.empty();
        return new MavenModel(
            parentField(root, "groupId"),
            parentField(root, "artifactId"),
            parentField(root, "version"),
            collectDependencies(root),
            collectPlugins(root));
    }

    public Optional<MavenModel> parseAtRoot(Path projectRoot) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) return Optional.empty();
        try {
            return Optional.of(parse(Files.readString(pom)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Element parseRoot(String pomXml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            var doc = builder.parse(new ByteArrayInputStream(pomXml.getBytes(StandardCharsets.UTF_8)));
            return doc.getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return null;
        }
    }

    private static String parentField(Element root, String tag) {
        Element parent = firstChild(root, "parent");
        return parent == null ? "" : textOf(parent, tag);
    }

    private static List<Dependency> collectDependencies(Element root) {
        var out = new ArrayList<Dependency>();
        NodeList deps = root.getElementsByTagName("dependency");
        for (int i = 0; i < deps.getLength(); i++) {
            var el = (Element) deps.item(i);
            String groupId = textOf(el, "groupId");
            String artifactId = textOf(el, "artifactId");
            String version = textOf(el, "version");
            String scope = textOf(el, "scope");
            if (artifactId.isBlank()) continue;
            String name = groupId.isBlank() ? artifactId : groupId + ":" + artifactId;
            out.add(new Dependency(name, blankToNull(version), scope.isBlank() ? "runtime" : scope));
        }
        return out;
    }

    private static List<PluginCoordinate> collectPlugins(Element root) {
        var out = new ArrayList<PluginCoordinate>();
        NodeList plugins = root.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            var el = (Element) plugins.item(i);
            String artifactId = textOf(el, "artifactId");
            if (artifactId.isBlank()) continue;
            out.add(new PluginCoordinate(
                textOf(el, "groupId"),
                artifactId,
                textOf(el, "version")));
        }
        return out;
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getParentNode() == parent) return (Element) n;
        }
        return null;
    }

    private static String textOf(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getParentNode() == parent) {
                String t = n.getTextContent();
                return t == null ? "" : t.strip();
            }
        }
        return "";
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s;
    }
}
