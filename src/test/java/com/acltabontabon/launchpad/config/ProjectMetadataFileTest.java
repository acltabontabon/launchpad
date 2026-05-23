package com.acltabontabon.launchpad.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectMetadataFileTest {

    @Test
    void absentFileResolvesToEmpty(@TempDir Path tmp) {
        assertThat(ProjectMetadataFile.load(tmp)).isEmpty();
    }

    @Test
    void parsesAllThreeOptionalFields(@TempDir Path tmp) throws Exception {
        var launchpadDir = Files.createDirectory(tmp.resolve(".launchpad"));
        Files.writeString(launchpadDir.resolve("project.yml"), """
            tags: [backend, payments]
            workspace: shop
            relatedTo: [shop-frontend, shop-api]
            """);

        var loaded = ProjectMetadataFile.load(tmp);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().tags()).containsExactly("backend", "payments");
        assertThat(loaded.get().workspace()).isEqualTo("shop");
        assertThat(loaded.get().relatedTo()).containsExactly("shop-frontend", "shop-api");
    }

    @Test
    void missingFieldsDefaultToEmpty(@TempDir Path tmp) throws Exception {
        var launchpadDir = Files.createDirectory(tmp.resolve(".launchpad"));
        Files.writeString(launchpadDir.resolve("project.yml"), "workspace: solo\n");

        var loaded = ProjectMetadataFile.load(tmp).orElseThrow();

        assertThat(loaded.workspace()).isEqualTo("solo");
        assertThat(loaded.tags()).isEmpty();
        assertThat(loaded.relatedTo()).isEmpty();
    }

    @Test
    void brokenYamlIsTolerated(@TempDir Path tmp) throws Exception {
        var launchpadDir = Files.createDirectory(tmp.resolve(".launchpad"));
        // tags must be a list - integer triggers a deserialization error.
        Files.writeString(launchpadDir.resolve("project.yml"), "tags: 42\n");

        // Must not throw - registry reads cannot be blocked by a malformed override file.
        assertThat(ProjectMetadataFile.load(tmp)).isEmpty();
    }
}
