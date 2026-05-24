package com.acltabontabon.launchpad.scanner.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.doc.MkdocsConfigParser.MkdocsConfig;
import com.acltabontabon.launchpad.scanner.doc.MkdocsConfigParser.NavEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MkdocsConfigParserTest {

    @Test
    void returnsEmptyWhenFileMissing(@TempDir Path dir) {
        assertThat(MkdocsConfigParser.load(dir.resolve("mkdocs.yml"))).isEmpty();
    }

    @Test
    void parsesSiteNameAndDefaultsDocsDir(@TempDir Path dir) throws IOException {
        var file = dir.resolve("mkdocs.yml");
        Files.writeString(file, "site_name: My Project\n");
        MkdocsConfig cfg = MkdocsConfigParser.load(file).orElseThrow();
        assertThat(cfg.siteName()).isEqualTo("My Project");
        assertThat(cfg.docsDir()).isEqualTo("docs");
        assertThat(cfg.nav()).isEmpty();
    }

    @Test
    void honoursCustomDocsDir(@TempDir Path dir) throws IOException {
        var file = dir.resolve("mkdocs.yml");
        Files.writeString(file, "site_name: X\ndocs_dir: documentation\n");
        MkdocsConfig cfg = MkdocsConfigParser.load(file).orElseThrow();
        assertThat(cfg.docsDir()).isEqualTo("documentation");
    }

    @Test
    void flattensSimpleNav(@TempDir Path dir) throws IOException {
        var file = dir.resolve("mkdocs.yml");
        Files.writeString(file, """
            site_name: X
            nav:
              - Home: index.md
              - Quick Start: quickstart.md
            """);
        MkdocsConfig cfg = MkdocsConfigParser.load(file).orElseThrow();
        assertThat(cfg.nav()).containsExactly(
            new NavEntry("Home", "index.md"),
            new NavEntry("Quick Start", "quickstart.md")
        );
    }

    @Test
    void flattensNestedNavWithCompoundTitles(@TempDir Path dir) throws IOException {
        var file = dir.resolve("mkdocs.yml");
        Files.writeString(file, """
            site_name: X
            nav:
              - Home: index.md
              - User Guide:
                  - Writing Plugins: user-guide/plugins.md
                  - Configuration: user-guide/configuration.md
            """);
        MkdocsConfig cfg = MkdocsConfigParser.load(file).orElseThrow();
        assertThat(cfg.nav()).containsExactly(
            new NavEntry("Home", "index.md"),
            new NavEntry("User Guide / Writing Plugins", "user-guide/plugins.md"),
            new NavEntry("User Guide / Configuration", "user-guide/configuration.md")
        );
    }

    @Test
    void returnsEmptyOnMalformedYaml(@TempDir Path dir) throws IOException {
        var file = dir.resolve("mkdocs.yml");
        Files.writeString(file, "site_name: : : :\n\tnot yaml: [unclosed\n");
        assertThat(MkdocsConfigParser.load(file)).isEmpty();
    }

    @Test
    void ignoresUnknownTopLevelKeys(@TempDir Path dir) throws IOException {
        var file = dir.resolve("mkdocs.yml");
        Files.writeString(file, """
            site_name: X
            theme: material
            plugins:
              - search
              - mkdocstrings
            markdown_extensions:
              - admonition
            """);
        MkdocsConfig cfg = MkdocsConfigParser.load(file).orElseThrow();
        assertThat(cfg.siteName()).isEqualTo("X");
        assertThat(cfg.docsDir()).isEqualTo("docs");
    }
}
