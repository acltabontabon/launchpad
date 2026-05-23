package com.acltabontabon.launchpad.tui.theme;

import dev.tamboui.style.Color;

/**
 * Cosmic Console design tokens. Every view references semantic names from this
 * class; raw {@code Color.Rgb} constants and {@code Color.CYAN}-style ANSI
 * references should not appear elsewhere in the TUI layer.
 *
 * Palette: indigo brand + amber action duotone, emerald for go, rose for abort,
 * cool grays for hierarchy. Backgrounds are only painted on chips and small
 * surface accents - never across whole panels - so the terminal's own theme is
 * preserved as the canvas.
 */
public final class Theme {

    private Theme() {}

    // Brand
    public static final Color brand = Color.rgb(129, 140, 248);     // indigo-400
    public static final Color brandDim = Color.rgb(99, 102, 241);   // indigo-500

    // Action / focus
    public static final Color fuel = Color.rgb(251, 146, 60);       // orange-400
    public static final Color ignition = Color.rgb(252, 211, 77);   // amber-300

    // Semantic
    public static final Color go = Color.rgb(74, 222, 128);         // emerald-400
    public static final Color caution = Color.rgb(250, 204, 21);    // yellow-400
    public static final Color abort = Color.rgb(248, 113, 113);     // red-400

    // Text hierarchy
    public static final Color text = Color.rgb(229, 231, 235);      // gray-200
    public static final Color muted = Color.rgb(156, 163, 175);     // gray-400
    public static final Color subtle = Color.rgb(107, 114, 128);    // gray-500

    // Surfaces
    public static final Color border = Color.rgb(75, 85, 99);       // gray-600
    public static final Color surface = Color.rgb(55, 65, 81);      // gray-700
}
