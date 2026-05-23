package com.acltabontabon.launchpad.tui.mcp;

import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Detects whether Launchpad is running as a GraalVM native image (the only
 * shipping form) or in DEV (jar / mvn / IDE). Distribution is native-only, so
 * only the NATIVE binary path is registered with MCP clients; DEV mode surfaces
 * a placeholder snippet for copy and refuses to write client config files.
 */
@Component
public class LaunchModeDetector {

    public LaunchMode detect() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null
            ? LaunchMode.NATIVE
            : LaunchMode.DEV;
    }

    /** Absolute path to the running native binary, or empty when not native. */
    public Optional<Path> nativeBinaryPath() {
        if (detect() != LaunchMode.NATIVE) return Optional.empty();
        return ProcessHandle.current().info().command().map(Path::of).map(Path::toAbsolutePath);
    }
}
