package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acltabontabon.launchpad.standards.Adapter;
import com.acltabontabon.launchpad.standards.AdapterOutput;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdapterResolverLegacyAliasTest {

    @Test
    void agentsAdapterResolvesDirectly() {
        var loader = mock(StandardsLoader.class);
        var adapter = new Adapter("agents", "agents", "Vendor-neutral adapter",
            List.of(new AdapterOutput("CUSTOM.md", "markdown", List.of("rules"), Map.of())));
        when(loader.loadAdapter(any(), eq("agents"))).thenReturn(Optional.of(adapter));

        var resolved = new AdapterResolver(loader).resolve(Path.of("/tmp/x"));

        assertThat(resolved.primaryPath()).isEqualTo("CUSTOM.md");
        assertThat(resolved.adapter()).isPresent();
    }

    @Test
    void legacyClaudeAdapterIsAcceptedWhenAgentsMissing() {
        var loader = mock(StandardsLoader.class);
        var legacy = new Adapter("claude", "claude", "Legacy adapter",
            List.of(new AdapterOutput("CUSTOM-LEGACY.md", "markdown", List.of("rules"), Map.of())));
        when(loader.loadAdapter(any(), eq("agents"))).thenReturn(Optional.empty());
        when(loader.loadAdapter(any(), eq("claude"))).thenReturn(Optional.of(legacy));

        var resolved = new AdapterResolver(loader).resolve(Path.of("/tmp/x"));

        assertThat(resolved.primaryPath()).isEqualTo("CUSTOM-LEGACY.md");
        assertThat(resolved.adapter()).isPresent();
    }

    @Test
    void noAdapterFallsBackToAgentsMdDefault() {
        var loader = mock(StandardsLoader.class);
        when(loader.loadAdapter(any(), any())).thenReturn(Optional.empty());

        var resolved = new AdapterResolver(loader).resolve(Path.of("/tmp/x"));

        assertThat(resolved.primaryPath()).isEqualTo("AGENTS.md");
        assertThat(resolved.adapter()).isEmpty();
    }

    @Test
    void legacyCursorAdapterIsIgnoredAndFallsBackToDefault() {
        var loader = mock(StandardsLoader.class);
        var legacyCursor = new Adapter("cursor", "cursor", "Legacy cursor adapter",
            List.of(new AdapterOutput(".cursorrules", "markdown", List.of("rules"), Map.of())));
        when(loader.loadAdapter(any(), eq("agents"))).thenReturn(Optional.empty());
        when(loader.loadAdapter(any(), eq("claude"))).thenReturn(Optional.empty());
        when(loader.loadAdapter(any(), eq("cursor"))).thenReturn(Optional.of(legacyCursor));

        var resolved = new AdapterResolver(loader).resolve(Path.of("/tmp/x"));

        assertThat(resolved.primaryPath()).isEqualTo("AGENTS.md");
        assertThat(resolved.adapter()).isEmpty();
    }
}
