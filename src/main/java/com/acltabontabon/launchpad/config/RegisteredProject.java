package com.acltabontabon.launchpad.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * One entry in the user's local project registry. Identifies a project the
 * user has actually used Launchpad on, so MCP clients (and the TUI itself)
 * can address it by short name instead of an absolute path.
 * <p>
 * The relationship fields ({@link #tags}, {@link #workspace}, {@link #relatedTo})
 * are <em>not</em> persisted to the registry file. The source of truth lives
 * inside each project at {@code .launchpad/project.yml}; the registry overlays
 * that file on every {@link ProjectRegistry#all()} call. Storing them only in
 * the project means they travel with the codebase (survive registry wipes,
 * show up in code review, work across machines).
 *
 * @param name           human-friendly short name; unique per registry
 * @param path           absolute path to the project root on this machine
 * @param stack          short stack label (e.g. "Java/Spring Boot"), best-effort
 * @param addedAt        when the project was first registered
 * @param lastScannedAt  last time Launchpad scanned this project (may be null)
 * @param tags           free-form labels overlaid from .launchpad/project.yml
 * @param workspace      logical grouping name from .launchpad/project.yml
 * @param relatedTo      registered project names this project explicitly relates to
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record RegisteredProject(
    String name,
    String path,
    String stack,
    Instant addedAt,
    Instant lastScannedAt,
    List<String> tags,
    String workspace,
    List<String> relatedTo
) {
    public RegisteredProject {
        tags = tags == null ? List.of() : List.copyOf(tags);
        relatedTo = relatedTo == null ? List.of() : List.copyOf(relatedTo);
    }

    /** Convenience for callers that don't carry metadata (registry persistence). */
    public RegisteredProject(String name, String path, String stack,
                             Instant addedAt, Instant lastScannedAt) {
        this(name, path, stack, addedAt, lastScannedAt, List.of(), null, List.of());
    }

    public RegisteredProject withLastScannedAt(Instant when) {
        return new RegisteredProject(name, path, stack, addedAt, when, tags, workspace, relatedTo);
    }

    public RegisteredProject withStack(String newStack) {
        return new RegisteredProject(name, path, newStack, addedAt, lastScannedAt, tags, workspace, relatedTo);
    }

    public RegisteredProject withMetadata(List<String> newTags, String newWorkspace,
                                          List<String> newRelatedTo) {
        return new RegisteredProject(name, path, stack, addedAt, lastScannedAt,
            newTags, newWorkspace, newRelatedTo);
    }
}
