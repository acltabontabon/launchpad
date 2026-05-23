package com.acltabontabon.launchpad.scanner;

import com.acltabontabon.launchpad.scanner.DocumentationIndex.Format;
import com.acltabontabon.launchpad.scanner.DocumentationPage.PageFormat;
import com.acltabontabon.launchpad.scanner.MkdocsConfigParser.MkdocsConfig;
import com.acltabontabon.launchpad.scanner.MkdocsConfigParser.NavEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which {@link DocumentationIndex} a project ends up with. Branches:
 * <ol>
 *   <li>{@code mkdocs.yml} present -> MkDocs mode, page list ordered by
 *       declared nav (falls back to file-walk order).</li>
 *   <li>{@code antora.yml} present alongside {@code .adoc} files -> Antora
 *       mode, all {@code .adoc} files listed in walk order.</li>
 *   <li>Otherwise, if any doc files exist -> plain mode, all files listed.</li>
 *   <li>Otherwise -> {@link Format#NONE}.</li>
 * </ol>
 * <p>
 * Title extraction reads each file lazily through {@link DocTitleExtractor}.
 */
final class DocumentationDetector {

    DocumentationIndex detect(Path projectRoot, ScanSignals signals) {
        if (signals.hasMkdocsConfig) {
            return detectMkdocs(projectRoot, signals);
        }
        boolean anyAdoc = signals.docFiles.stream().anyMatch(DocumentationDetector::isAsciiDoc);
        if (signals.hasAntoraConfig && anyAdoc) {
            return detectAntora(projectRoot, signals);
        }
        if (!signals.docFiles.isEmpty()) {
            return detectPlain(projectRoot, signals);
        }
        return DocumentationIndex.none();
    }

    private DocumentationIndex detectMkdocs(Path projectRoot, ScanSignals signals) {
        var mkdocsYml = projectRoot.resolve("mkdocs.yml");
        var cfg = MkdocsConfigParser.load(mkdocsYml).orElse(null);
        String docsDir = cfg == null ? MkdocsConfigParser.DEFAULT_DOCS_DIR : cfg.docsDir();
        String siteName = cfg == null ? null : cfg.siteName();
        var pages = new ArrayList<DocumentationPage>();
        var emitted = new LinkedHashSet<String>();

        if (cfg != null && !cfg.nav().isEmpty()) {
            for (NavEntry nav : cfg.nav()) {
                var rel = joinRelative(docsDir, nav.pagePath());
                var file = projectRoot.resolve(rel);
                if (!Files.isRegularFile(file)) continue;
                PageFormat fmt = formatOf(nav.pagePath());
                if (fmt == null) continue;
                String title = nav.title() != null && !nav.title().isBlank()
                    ? nav.title()
                    : titleFromFile(file, fmt);
                pages.add(new DocumentationPage(rel, title, fmt));
                emitted.add(rel);
            }
        }

        // Pick up any doc file under docs_dir that wasn't named in nav (or any
        // file at all when nav is absent). Matches MkDocs's own behaviour:
        // unlisted pages still get rendered.
        String docsDirPrefix = normalisePrefix(docsDir);
        for (String rel : signals.docFiles) {
            if (!rel.startsWith(docsDirPrefix)) continue;
            if (emitted.contains(rel)) continue;
            PageFormat fmt = formatOf(rel);
            if (fmt == null) continue;
            var file = projectRoot.resolve(rel);
            pages.add(new DocumentationPage(rel, titleFromFile(file, fmt), fmt));
            emitted.add(rel);
        }

        return new DocumentationIndex(Format.MKDOCS, siteName, docsDir, List.copyOf(pages));
    }

    private DocumentationIndex detectAntora(Path projectRoot, ScanSignals signals) {
        var antoraYml = findFirst(projectRoot, signals.docFiles, "antora.yml");
        String siteName = null;
        if (antoraYml != null) {
            var cfg = AntoraConfigParser.load(antoraYml).orElse(null);
            if (cfg != null) {
                siteName = cfg.title() != null ? cfg.title() : cfg.name();
            }
        }
        var pages = pagesFromWalk(projectRoot, signals.docFiles, true);
        return new DocumentationIndex(Format.ANTORA, siteName, null, pages);
    }

    private DocumentationIndex detectPlain(Path projectRoot, ScanSignals signals) {
        var pages = pagesFromWalk(projectRoot, signals.docFiles, false);
        if (pages.isEmpty()) return DocumentationIndex.none();
        return new DocumentationIndex(Format.PLAIN, null, null, pages);
    }

    private List<DocumentationPage> pagesFromWalk(Path projectRoot, List<String> docFiles, boolean adocOnly) {
        var out = new ArrayList<DocumentationPage>();
        Set<String> seen = new LinkedHashSet<>();
        for (String rel : docFiles) {
            if (seen.contains(rel)) continue;
            PageFormat fmt = formatOf(rel);
            if (fmt == null) continue;
            if (adocOnly && fmt != PageFormat.ASCIIDOC) continue;
            out.add(new DocumentationPage(rel, titleFromFile(projectRoot.resolve(rel), fmt), fmt));
            seen.add(rel);
        }
        return out;
    }

    /** Look up an antora.yml referenced anywhere in the doc-file list. */
    private static Path findFirst(Path projectRoot, List<String> docFiles, String name) {
        for (String rel : docFiles) {
            int slash = rel.lastIndexOf('/');
            String base = slash >= 0 ? rel.substring(slash + 1) : rel;
            if (base.equals(name)) return projectRoot.resolve(rel);
        }
        var atRoot = projectRoot.resolve(name);
        return Files.isRegularFile(atRoot) ? atRoot : null;
    }

    static PageFormat formatOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return PageFormat.MARKDOWN;
        if (lower.endsWith(".adoc") || lower.endsWith(".asciidoc")) return PageFormat.ASCIIDOC;
        if (lower.endsWith(".rst")) return PageFormat.RST;
        return null;
    }

    static boolean isAsciiDoc(String path) {
        return formatOf(path) == PageFormat.ASCIIDOC;
    }

    private static String titleFromFile(Path file, PageFormat fmt) {
        String content = readPrefix(file);
        return DocTitleExtractor.extract(file.getFileName().toString(), fmt, content);
    }

    /** Read only the first chunk of the file - enough to find a heading. */
    private static String readPrefix(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            int limit = Math.min(bytes.length, DocTitleExtractor.SCAN_CHARS);
            return new String(bytes, 0, limit, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Join {@code docs_dir} and a nav-relative page path into a project-relative path. */
    private static String joinRelative(String docsDir, String pagePath) {
        if (pagePath == null) return "";
        String d = normalisePrefix(docsDir);
        return d + pagePath;
    }

    private static String normalisePrefix(String docsDir) {
        if (docsDir == null || docsDir.isBlank()) return "";
        return docsDir.endsWith("/") ? docsDir : docsDir + "/";
    }
}
