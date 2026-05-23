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

    /**
     * Placeholder snippet used by the GENERIC option when there's no native
     * binary to point at (DEV mode). Surfaces a clearly-bogus path so the user
     * knows to substitute it with the path to their installed launchpad binary.
     */
    public McpSnippet placeholder() {
        return new McpSnippet("/abs/path/to/launchpad", List.of("mcp"), Map.of());
    }

    /** Render a snippet as a standalone JSON document (the GENERIC "copy" view). */
    public String renderStandalone(McpSnippet snippet) {
        return JsonMcpMerger.renderStandalone(snippet);
    }
}
