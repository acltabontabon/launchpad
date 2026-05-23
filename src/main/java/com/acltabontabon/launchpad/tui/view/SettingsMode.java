package com.acltabontabon.launchpad.tui.view;

/**
 * Sub-modes of the /settings screen. FIELDS is the default (the three property
 * inputs). MCP_PICKER, MCP_CONFIRM, MCP_RESULT drive the "Connect to AI tool"
 * flow that wires Launchpad's MCP server into Claude / Cursor config files.
 */
public enum SettingsMode {
    FIELDS,
    MCP_PICKER,
    MCP_CONFIRM,
    MCP_RESULT
}
