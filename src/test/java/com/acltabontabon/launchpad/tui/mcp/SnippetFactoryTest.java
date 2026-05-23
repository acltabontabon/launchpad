package com.acltabontabon.launchpad.tui.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SnippetFactoryTest {

    @Test
    void nativeModeUsesBinaryDirectly() {
        var detector = mock(LaunchModeDetector.class);
        when(detector.nativeBinaryPath()).thenReturn(Optional.of(Path.of("/usr/local/bin/launchpad")));

        var snippet = new SnippetFactory(detector).build(LaunchMode.NATIVE).orElseThrow();

        assertThat(snippet.command()).isEqualTo("/usr/local/bin/launchpad");
        assertThat(snippet.args()).containsExactly("mcp");
    }

    @Test
    void devModeReturnsEmpty() {
        var detector = mock(LaunchModeDetector.class);
        assertThat(new SnippetFactory(detector).build(LaunchMode.DEV)).isEmpty();
    }

    @Test
    void nativeModeReturnsEmptyWhenBinaryPathMissing() {
        var detector = mock(LaunchModeDetector.class);
        when(detector.nativeBinaryPath()).thenReturn(Optional.empty());
        assertThat(new SnippetFactory(detector).build(LaunchMode.NATIVE)).isEmpty();
    }

}
