package com.acltabontabon.launchpad.scanner;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Recognises that a project at a given path belongs to a supported framework.
 * Each supported framework contributes one {@code ProjectSupportSignal} bean;
 * {@link ProjectSupportDetector} consults the full list and treats the first
 * positive match as authoritative.
 *
 * <p>This is the extension seam for the project-support gate. Adding a new
 * supported stack (e.g. Spring Boot on Gradle, or a different framework
 * entirely) means dropping in one more {@code @Component} that knows how to
 * recognise its own build files - no edits to {@link ProjectSupportDetector}
 * are needed. The signal is deliberately deterministic: it reads structured
 * project metadata (parsed pom, build.gradle DSL, etc.) and returns a
 * concrete match rather than a fuzzy confidence score.
 *
 * <p>A signal returning {@link Optional#empty()} simply means "I do not
 * recognise this project"; it never means "definitely unsupported". The
 * detector concludes "unsupported" only when every registered signal abstains.
 */
public interface ProjectSupportSignal {

    /**
     * Inspect the project root and report a match when this signal's
     * framework is present. Implementations must be deterministic: parse
     * structured metadata rather than grepping raw text where possible.
     *
     * @param projectRoot absolute path to the project directory
     * @return present when the project is recognised as this framework
     */
    Optional<Match> evaluate(Path projectRoot);

    /**
     * A positive match. {@code framework} is the human-readable name used in
     * error messages and telemetry (e.g. "Spring Boot Java + Maven"); it does
     * not need to equal any particular {@link StackProfile#framework()} string.
     */
    record Match(String framework) {

        public Match {
            if (framework == null || framework.isBlank()) {
                throw new IllegalArgumentException("framework must be non-blank");
            }
        }
    }
}
