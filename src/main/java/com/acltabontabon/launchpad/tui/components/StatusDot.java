package com.acltabontabon.launchpad.tui.components;

import com.acltabontabon.launchpad.tui.theme.Icons;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

/**
 * Coloured status dot + label. Used in system-check cards, validation feedback,
 * and the bottom-right of the footer.
 */
public final class StatusDot {

    private StatusDot() {}

    public enum State {
        OK(Theme.go, Icons.DOT_FILLED),
        WORKING(Theme.fuel, Icons.DOT_HALF),
        WARN(Theme.caution, Icons.DOT_FILLED),
        ERROR(Theme.abort, Icons.DOT_FILLED),
        IDLE(Theme.subtle, Icons.DOT_EMPTY);

        public final Color color;
        public final String glyph;

        State(Color color, String glyph) {
            this.color = color;
            this.glyph = glyph;
        }
    }

    /** Just the coloured dot glyph - useful when composing inline. */
    public static Span dot(State state) {
        return Span.styled(state.glyph, Style.create().fg(state.color));
    }

    /** Coloured dot + space + label rendered in body text. */
    public static Line of(State state, String label) {
        return Line.from(
            Span.styled(state.glyph, Style.create().fg(state.color)),
            Span.styled("  " + label, Style.create().fg(Theme.text))
        );
    }

    /** Coloured dot + space + label + extra muted detail. */
    public static Line of(State state, String label, String detail) {
        return Line.from(
            Span.styled(state.glyph, Style.create().fg(state.color)),
            Span.styled("  " + label + "  ", Style.create().fg(Theme.text).bold()),
            Span.styled(detail, Style.create().fg(Theme.muted))
        );
    }
}
