package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectScriptsProviderTest {

    @Test
    void readsScriptsFolderAndFirstComment(@TempDir Path root) throws Exception {
        var scripts = root.resolve("scripts");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("build.sh"), """
            #!/usr/bin/env bash
            # Build and package the application
            ./mvnw package
            """);
        Files.writeString(scripts.resolve("deploy.sh"), """
            #!/bin/sh
            # Deploy to prod
            echo deploying
            """);

        var catalog = ProjectScriptsProvider.catalog(root);
        assertThat(catalog).containsKeys("scripts/build.sh", "scripts/deploy.sh");
        assertThat(catalog.get("scripts/build.sh")).isEqualTo("Build and package the application");
        assertThat(catalog.get("scripts/deploy.sh")).isEqualTo("Deploy to prod");
    }

    @Test
    void readsMakefileTargetsWithDoubleHashDocs(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Makefile"), """
            .PHONY: test build

            ## test: run the suite
            test:
            \t./mvnw test

            ## build: package the jar
            build:
            \t./mvnw package
            """);

        var catalog = ProjectScriptsProvider.catalog(root);
        assertThat(catalog).containsKeys("make test", "make build");
        assertThat(catalog.get("make test")).contains("run the suite");
        assertThat(catalog.get("make build")).contains("package the jar");
    }

    @Test
    void emptyCatalogWhenNoScriptsOrMakefile(@TempDir Path root) {
        assertThat(ProjectScriptsProvider.catalog(root)).isEmpty();
    }

    @Test
    void emptyCatalogForNullOrMissingRoot() {
        assertThat(ProjectScriptsProvider.catalog(null)).isEmpty();
        assertThat(ProjectScriptsProvider.catalog(Path.of("/does/not/exist"))).isEmpty();
    }
}
