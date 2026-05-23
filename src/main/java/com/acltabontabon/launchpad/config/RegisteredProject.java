package com.acltabontabon.launchpad.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * One entry in the user's local project registry. Identifies a project the
 * user has actually used Launchpad on, so MCP clients (and the TUI itself)
 * can address it by short name instead of an absolute path.
 *
 * @param name           human-friendly short name; unique per registry
 * @param path           absolute path to the project root on this machine
 * @param stack          short stack label (e.g. "Java/Spring Boot"), best-effort
 * @param addedAt        when the project was first registered
 * @param lastScannedAt  last time Launchpad scanned this project (may be null)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisteredProject(
    String name,
    String path,
    String stack,
    Instant addedAt,
    Instant lastScannedAt
) {
    public RegisteredProject withLastScannedAt(Instant when) {
        return new RegisteredProject(name, path, stack, addedAt, when);
    }

    public RegisteredProject withStack(String newStack) {
        return new RegisteredProject(name, path, newStack, addedAt, lastScannedAt);
    }
}
