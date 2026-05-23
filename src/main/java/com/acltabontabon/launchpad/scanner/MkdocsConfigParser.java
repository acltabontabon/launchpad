package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Light-weight reader for {@code mkdocs.yml}. Extracts only the three fields
 * Launchpad cares about: {@code site_name}, {@code docs_dir} (defaulting to
 * "docs"), and a flattened {@code nav} declaration.
 * <p>
 * Failures (file missing, malformed YAML, unexpected types) return
 * {@link Optional#empty()} and log once at WARN. We intentionally do not try
 * to be a MkDocs renderer: plugins, theme settings, markdown extensions, and
 * everything else are ignored.
 */
final class MkdocsConfigParser {

    private static final Logger log = LoggerFactory.getLogger(MkdocsConfigParser.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Default MkDocs docs directory when {@code docs_dir} is not set. */
    static final String DEFAULT_DOCS_DIR = "docs";

    record MkdocsConfig(String siteName, String docsDir, List<NavEntry> nav) {

        public MkdocsConfig {
            nav = nav == null ? List.of() : List.copyOf(nav);
        }
    }

    /** Flattened nav entry: a title paired with the doc-relative page path. */
    record NavEntry(String title, String pagePath) {}

    static Optional<MkdocsConfig> load(Path mkdocsYml) {
        if (!Files.isRegularFile(mkdocsYml)) return Optional.empty();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = YAML.readValue(mkdocsYml.toFile(), Map.class);
            if (root == null) return Optional.empty();
            String siteName = asString(root.get("site_name"));
            String docsDir = asString(root.get("docs_dir"));
            if (docsDir == null || docsDir.isBlank()) docsDir = DEFAULT_DOCS_DIR;
            List<NavEntry> nav = flattenNav(root.get("nav"));
            return Optional.of(new MkdocsConfig(siteName, docsDir, nav));
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read {} - ignoring MkDocs config: {}", mkdocsYml, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * MkDocs nav grammar: a list whose items are either a single-key map
     * {@code Title: page.md} or {@code Title: [list of nested items]}. We
     * flatten the whole tree, joining nested titles with " / " so the page
     * list still carries some hierarchy in its display title.
     */
    private static List<NavEntry> flattenNav(Object navObj) {
        var out = new ArrayList<NavEntry>();
        if (!(navObj instanceof List<?> top)) return out;
        for (Object item : top) {
            collect(item, "", out);
        }
        return out;
    }

    private static void collect(Object item, String prefix, List<NavEntry> out) {
        if (item instanceof String s) {
            out.add(new NavEntry(prefix.isEmpty() ? humaniseFile(s) : prefix, s));
            return;
        }
        if (!(item instanceof Map<?, ?> m) || m.size() != 1) return;
        var entry = m.entrySet().iterator().next();
        String title = String.valueOf(entry.getKey());
        String compoundTitle = prefix.isEmpty() ? title : prefix + " / " + title;
        Object value = entry.getValue();
        if (value instanceof String s) {
            out.add(new NavEntry(compoundTitle, s));
        } else if (value instanceof List<?> children) {
            for (Object child : children) {
                collect(child, compoundTitle, out);
            }
        }
        // Unsupported shapes (Map -> Map, null page reference, etc.) are skipped.
    }

    private static String humaniseFile(String file) {
        int slash = file.lastIndexOf('/');
        String name = slash >= 0 ? file.substring(slash + 1) : file;
        return DocTitleExtractor.humaniseStem(name);
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private MkdocsConfigParser() {}
}
