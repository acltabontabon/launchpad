package com.acltabontabon.launchpad.tui.theme;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Semantic Style builders. Views call {@link #heading()}, {@link #caption()},
 * etc. instead of {@code Style.create().fg(...)}. Swapping the design language
 * later means editing this file, not every view.
 */
public final class Styles {

    private Styles() {}

    // Typography ──────────────────────────────────────────────────────────────

    public static Style heading() {
        return Style.create().fg(Theme.text).bold();
    }

    public static Style brandHeading() {
        return Style.create().fg(Theme.brand).bold();
    }

    public static Style subheading() {
        return Style.create().fg(Theme.muted).bold();
    }

    public static Style body() {
        return Style.create().fg(Theme.text);
    }

    public static Style muted() {
        return Style.create().fg(Theme.muted);
    }

    public static Style caption() {
        return Style.create().fg(Theme.subtle).italic();
    }

    public static Style hint() {
        return Style.create().fg(Theme.muted);
    }

    public static Style code() {
        return Style.create().fg(Theme.text);
    }

    public static Style dim() {
        return Style.create().fg(Theme.subtle);
    }

    // Semantic ────────────────────────────────────────────────────────────────

    public static Style success() {
        return Style.create().fg(Theme.go);
    }

    public static Style warning() {
        return Style.create().fg(Theme.caution);
    }

    public static Style error() {
        return Style.create().fg(Theme.abort).bold();
    }

    public static Style focus() {
        return Style.create().fg(Theme.fuel).bold();
    }

    // Chips ───────────────────────────────────────────────────────────────────

    public static Style chip(Color bg, Color fg) {
        return Style.create().fg(fg).bg(bg).bold();
    }

    public static Style activeChip() {
        return chip(Theme.fuel, Color.rgb(15, 23, 42));
    }

    public static Style muteChip() {
        return chip(Theme.surface, Theme.muted);
    }

    public static Style successChip() {
        return chip(Theme.go, Color.rgb(15, 23, 42));
    }

    public static Style dangerChip() {
        return chip(Theme.abort, Color.rgb(15, 23, 42));
    }

    public static Style cautionChip() {
        return chip(Theme.caution, Color.rgb(15, 23, 42));
    }

    public static Style brandChip() {
        return chip(Theme.brand, Color.rgb(15, 23, 42));
    }

    // List / selection ────────────────────────────────────────────────────────

    public static Style listHighlight() {
        return Style.create().fg(Theme.fuel).bold();
    }

    // Diff ────────────────────────────────────────────────────────────────────

    public static Style diffAdd() {
        return Style.create().fg(Theme.go);
    }

    public static Style diffRemove() {
        return Style.create().fg(Theme.abort);
    }

    public static Style diffContext() {
        return Style.create().fg(Theme.subtle);
    }
}
