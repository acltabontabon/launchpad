package com.acltabontabon.launchpad.scanner.build;

import com.acltabontabon.launchpad.scanner.Dependency;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A build tool Launchpad can read structured project metadata from. Each
 * supported build tool contributes one {@code BuildSystem} strategy;
 * {@link BuildSystemDetector} consults the registered list and treats the
 * first match as authoritative.
 *
 * <p>This is the seam that keeps the scanner build-tool-agnostic: the stack
 * label, declared dependencies, and canonical build commands all come from
 * the resolved {@code BuildSystem} rather than from hardcoded Maven
 * assumptions. Adding a new build tool means dropping in one more
 * implementation - no edits to the scanner pipeline. It mirrors the
 * {@link com.acltabontabon.launchpad.scanner.ProjectSupportSignal} seam used
 * by the project-support gate, and reuses the same structured parsers so the
 * two never diverge in how they read a build file.
 *
 * <p>{@code keyFiles} is keyed by basename (e.g. {@code pom.xml},
 * {@code build.gradle}) - the same map the scanner already passes to its
 * detectors.
 */
public interface BuildSystem {

    /** Display + persisted label, e.g. {@code "Maven"} / {@code "Gradle"}. */
    String name();

    /** True when this build tool's files are present at the project root. */
    boolean matches(Path root, Map<String, String> keyFiles);

    /** Canonical build + test commands shown in generated docs. */
    List<String> buildCommands();

    /** Declared dependencies parsed from this build tool's files. */
    List<Dependency> dependencies(Map<String, String> keyFiles);
}
