package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.scanner.ProjectScanner;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Round-5 resilience contract: a "naked" project (no README, no Maven, no
 * controllers, no standards) must still produce a renderable, honest
 * CLAUDE.md skeleton. Not in the default surefire pattern - run explicitly:
 *
 *   ./mvnw test -Dtest=NakedProjectVerification
 *
 * The fixture is a single empty `.gitkeep` so the scanner has a directory
 * to walk. Synthesis is disabled (generator == null) so this test is
 * deterministic and does not require Ollama.
 */
class NakedProjectVerification {

    @Test
    void primaryFileSurvivesWithNoSignals() throws Exception {
        var fixture = Path.of("src/test/resources/fixtures/naked-project");
        var ctx = ProjectScanner.forTesting().scan(fixture.toString(), msg -> {});

        var loader = Mockito.mock(StandardsLoader.class);
        Mockito.when(loader.loadRules(Mockito.any())).thenReturn(List.of());
        Mockito.when(loader.loadSkills(Mockito.any())).thenReturn(List.of());
        Mockito.when(loader.loadChecklists(Mockito.any())).thenReturn(List.of());
        Mockito.when(loader.loadPrompts(Mockito.any())).thenReturn(List.of());
        Mockito.when(loader.loadAdapter(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        var registry = Mockito.mock(ProjectRegistry.class);
        Mockito.when(registry.all()).thenReturn(List.of());

        var engine = new ContextTemplateEngine(loader, registry, null);
        var files = engine.buildFiles(ctx, ContextTarget.CLAUDE, "", "");

        var primary = files.stream()
            .filter(f -> f.relativePath().equals("CLAUDE.md"))
            .findFirst()
            .orElseThrow()
            .content();

        System.out.println("================ NAKED CLAUDE.md ================");
        System.out.println(primary);
        System.out.println("================ END ================");

        // Survival contract: title + tagline + boundaries always render.
        assertThat(primary).contains("# CLAUDE.md");
        assertThat(primary).doesNotContain("Launchpad prepares. Paid agents execute.");
        assertThat(primary).contains("## What this project is");
        assertThat(primary).contains("## Boundaries for AI agents");

        // Every conditional section drops because nothing in the scan
        // produced a fact for it.
        assertThat(primary).doesNotContain("## Commands");
        assertThat(primary).doesNotContain("## How to work in this repo");
        assertThat(primary).doesNotContain("## Project map");
        assertThat(primary).doesNotContain("## API surface");
        assertThat(primary).doesNotContain("## Build profiles");

        // No raw config / source dumps anywhere.
        assertThat(primary).doesNotContain("<project");
        assertThat(primary).doesNotContain("## Key Build / Config Excerpts");
    }
}
