package com.acltabontabon.launchpad.tui.mcp;

import java.nio.file.Path;

/**
 * One row in the connect picker. {@code configPath} is null for the GENERIC entry,
 * which has no file target and only renders the snippet for copy.
 */
public record AiClient(ClientId id, String displayName, Path configPath, boolean detected) { }
