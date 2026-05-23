package com.acltabontabon.launchpad.tui.mcp;

import java.nio.file.Path;

/**
 * One row in the connect picker. {@code configPath} is null for the GENERIC entry,
 * which has no file target and only renders the snippet for copy. {@code alreadyLinked}
 * is true when the existing config file at {@code configPath} already contains a
 * {@code mcpServers.launchpad} entry - the picker renders such rows as pre-ticked
 * and non-toggleable so the user can see at a glance what is already wired up.
 */
public record AiClient(ClientId id, String displayName, Path configPath,
                       boolean detected, boolean alreadyLinked) { }
