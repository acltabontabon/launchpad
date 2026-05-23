package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.DocumentationIndex.Format;
import com.acltabontabon.launchpad.scanner.DocumentationPage.PageFormat;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Drives the full {@link ProjectScanner} against the two doc-shaped fixtures
 * under {@code src/test/resources/fixtures/}. Catches regressions where the
 * detector works in isolation but the scanner forgets to feed it the right
 * signals.
 */
class DocumentationScanIntegrationTest {

    private final ProjectScanner scanner = ProjectScanner.forTesting();

    @Test
    void mkdocsFixtureProducesMkdocsIndexOrderedByNav() throws Exception {
        var ctx = scanner.scan(fixturePath("mkdocs-sample").toString(), msg -> { });
        var docs = ctx.documentation();
        assertThat(docs.format()).isEqualTo(Format.MKDOCS);
        assertThat(docs.siteName()).isEqualTo("Sample Library");
        assertThat(docs.docsDir()).isEqualTo("docs");
        assertThat(docs.pages())
            .extracting(DocumentationPage::path)
            .containsExactly(
                "docs/index.md",
                "docs/guide/setup.md",
                "docs/guide/configuration.md");
        assertThat(docs.pages().get(0).title()).isEqualTo("Home");
        assertThat(docs.pages().get(1).title()).isEqualTo("User Guide / Setup");
        assertThat(docs.pages().get(0).format()).isEqualTo(PageFormat.MARKDOWN);
    }

    @Test
    void asciidocFixtureProducesPlainAsciiDocIndex() throws Exception {
        var ctx = scanner.scan(fixturePath("asciidoc-sample").toString(), msg -> { });
        var docs = ctx.documentation();
        assertThat(docs.format()).isEqualTo(Format.PLAIN);
        assertThat(docs.pages())
            .extracting(DocumentationPage::path, DocumentationPage::format)
            .contains(
                org.assertj.core.api.Assertions.tuple("docs/index.adoc", PageFormat.ASCIIDOC),
                org.assertj.core.api.Assertions.tuple("docs/usage.adoc", PageFormat.ASCIIDOC));
        var titles = docs.pages().stream().map(DocumentationPage::title).toList();
        assertThat(titles).contains("Sample AsciiDoc Library", "Usage");
    }

    private static Path fixturePath(String name) {
        var p = new File("src/test/resources/fixtures/" + name).getAbsoluteFile().toPath();
        if (!p.toFile().isDirectory()) {
            throw new IllegalStateException("fixture missing: " + p);
        }
        return p;
    }
}
