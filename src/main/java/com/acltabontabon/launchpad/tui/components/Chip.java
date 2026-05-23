package com.acltabontabon.launchpad.tui.components;

import dev.tamboui.style.Style;
import dev.tamboui.text.Span;

/**
 * A small inline pill: {@code " label "} with a coloured background and
 * contrasting foreground. Use the static constructors for the canonical
 * variants; pass an explicit {@link Style} to build a custom one.
 */
public final class Chip {

    private Chip() {}

    public static Span of(String label, Style style) {
        return Span.styled(" " + label + " ", style);
    }
}
