package com.acltabontabon.launchpad.tui.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link McpSnippet} appropriate to the current launch mode. Returns
 * empty for DEV - we cannot point a client at a non-existent jar, so the picker
 * disables file-writing rows and only the GENERIC (copy) option remains.
 */
@Component
public class SnippetFactory {

    private final LaunchModeDetector detector;

    public SnippetFactory(LaunchModeDetector detector) {
        this.detector = detector;
    }

    public Optional<McpSnippet> build() {
        return build(detector.detect());
    }

    public Optional<McpSnippet> build(LaunchMode mode) {
        return switch (mode) {
            case NATIVE -> detector.nativeBinaryPath()
                .map(p -> new McpSnippet(p.toString(), List.of("mcp"), Map.of()));
            case DEV -> Optional.empty();
        };
    }
}
