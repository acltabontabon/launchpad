package com.acltabontabon.launchpad.scanner;

import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for "is this a project Launchpad can analyse." Used
 * by the TUI ProjectSelect screen and the MCP {@code scan_project} tool;
 * downstream scanner phases trust that any project that reaches them has
 * already cleared this gate.
 *
 * <p>Support recognition is delegated to the registered list of
 * {@link ProjectSupportSignal} beans. The detector treats the first positive
 * match as authoritative and returns {@link Result#unsupported(String)} only
 * when every signal abstains. This keeps the detector itself stable as new
 * frameworks come into scope - each new framework adds one
 * {@code ProjectSupportSignal} bean and the detector picks it up via DI.
 *
 * <p>The canonical unsupported reason names the recognised paths today
 * (Spring Boot Java on Maven or Gradle). As more signals come online the
 * reason copy can grow per-framework hints; the {@link Result} shape does
 * not change.
 */
@Component
public final class ProjectSupportDetector {

    static final String CANONICAL_REASON =
        "Launchpad currently supports Spring Boot Java projects (Maven or Gradle) only.";

    private final List<ProjectSupportSignal> signals;

    public ProjectSupportDetector(List<ProjectSupportSignal> signals) {
        this.signals = List.copyOf(signals);
    }

    public Result detect(Path projectRoot) {
        if (projectRoot == null) {
            return Result.unsupported(CANONICAL_REASON + " No project path given.");
        }
        for (var signal : signals) {
            var match = signal.evaluate(projectRoot);
            if (match.isPresent()) {
                return Result.supported(match.get().framework());
            }
        }
        return Result.unsupported(CANONICAL_REASON);
    }

    public sealed interface Result permits Supported, Unsupported {

        static Result supported(String framework) {
            return new Supported(framework);
        }

        static Result unsupported(String reason) {
            return new Unsupported(reason);
        }

        default boolean isSupported() {
            return this instanceof Supported;
        }

        default String reason() {
            return this instanceof Unsupported u ? u.reason : "";
        }

        default String framework() {
            return this instanceof Supported s ? s.framework : "";
        }
    }

    public record Supported(String framework) implements Result { }

    public record Unsupported(String reason) implements Result { }
}
