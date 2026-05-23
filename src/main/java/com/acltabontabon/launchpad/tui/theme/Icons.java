package com.acltabontabon.launchpad.tui.theme;

/**
 * Every glyph the TUI uses, named once. Keeps the visual vocabulary consistent
 * and makes a future glyph swap a one-line change.
 */
public final class Icons {

    private Icons() {}

    // Brand
    public static final String LAUNCH_MARK = "▲";
    public static final String BRAND = "◆";
    public static final String LAUNCH_COLUMN = "┃";

    // Status nodes
    public static final String DOT_FILLED = "●";
    public static final String DOT_HALF = "◐";
    public static final String DOT_EMPTY = "○";

    // Feedback
    public static final String CHECK = "✓";
    public static final String CROSS = "✗";
    public static final String WARN = "⚠";
    public static final String SPARK = "✨";

    // Navigation
    public static final String CURSOR = "▸";
    public static final String ARROW_RIGHT = "›";
    public static final String ARROW_TARGET = "→";
    public static final String SWAP = "↔";

    // Decorative
    public static final String STEP_BAR = "━";
    public static final String SEP = "·";
    public static final String BULLET = "·";

    // Spinner (10-frame braille). Frame indexing wraps via Math.floorMod.
    public static final String[] SPINNER = {
        "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    };
}
