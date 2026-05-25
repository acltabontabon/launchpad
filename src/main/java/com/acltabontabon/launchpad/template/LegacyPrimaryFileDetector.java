package com.acltabontabon.launchpad.template;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Detects legacy primary-instruction files (CLAUDE.md, .cursorrules) that
 * Launchpad no longer regenerates. The caller turns the result into a WARN
 * log line and a TUI Review banner. The generated AGENTS.md body itself
 * stays clean - migration housekeeping does not pollute the instruction
 * surface.
 */
public final class LegacyPrimaryFileDetector {

    private static final String[] LEGACY_NAMES = { "CLAUDE.md", ".cursorrules" };

    private LegacyPrimaryFileDetector() {}

    /**
     * Returns a single user-facing warning message naming any legacy files
     * present at {@code projectRoot} that are not in the new generated set.
     * Returns empty when nothing to surface.
     */
    public static Optional<String> detect(Path projectRoot, List<GeneratedFile> generatedFiles) {
        var emitted = new HashSet<String>();
        for (var f : generatedFiles) emitted.add(f.relativePath());
        var legacy = new ArrayList<String>();
        for (var name : LEGACY_NAMES) {
            if (emitted.contains(name)) continue;
            if (Files.isRegularFile(projectRoot.resolve(name))) legacy.add(name);
        }
        if (legacy.isEmpty()) return Optional.empty();
        return Optional.of("Legacy " + String.join(" / ", legacy)
            + " found at project root. Launchpad no longer maintains these files."
            + " The new AGENTS.md is the canonical primary; delete the legacy files when ready.");
    }
}
