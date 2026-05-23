package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Optional audit-time machinery on a rule. When absent, the rule is doc-only:
 * rendered into context files but never checked against the codebase. When
 * present, the corresponding {@code RuleChecker} runs against the project and
 * emits {@code Finding}s for matches.
 * <p>
 * Three kinds are recognized initially:
 * <ul>
 *   <li>{@code forbid-pattern} - regex over file contents. Reads {@code pattern}
 *       and optional {@code includes} / {@code excludes} globs.</li>
 *   <li>{@code forbid-import} - matches {@code import} lines against {@code imports}
 *       (each entry supports wildcard suffix {@code .**}). Optional {@code inPackages}
 *       restricts which source paths are inspected.</li>
 *   <li>{@code llm} - asks the local model a yes/no question per file selected by
 *       {@code targets} (controllers | services | configs | files-matching) with
 *       structured-output parsing. Reserved for rules that need semantic judgement.</li>
 * </ul>
 * Unknown fields are tolerated so packs authored against a newer Launchpad keep loading.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Check(
    String kind,
    String pattern,
    List<String> includes,
    List<String> excludes,
    List<String> inPackages,
    List<String> imports,
    String targets,
    String question
) {
    public Check {
        if (includes == null) includes = List.of();
        if (excludes == null) excludes = List.of();
        if (inPackages == null) inPackages = List.of();
        if (imports == null) imports = List.of();
    }
}
