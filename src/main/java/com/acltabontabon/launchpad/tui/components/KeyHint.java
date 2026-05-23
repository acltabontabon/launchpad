package com.acltabontabon.launchpad.tui.components;

import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * A single {@code key + label} pair shown in the footer. Each view declares
 * its hints via {@code View.footerHints(state)} and the {@link Footer} renders
 * them as: {@code [ tab ] autocomplete   [ enter ] continue   [ esc ] back}.
 */
public record KeyHint(String key, String label) {

    /** Renders a sequence of hints to a list of spans, separated by spaces. */
    public static List<Span> render(List<KeyHint> hints) {
        var out = new ArrayList<Span>();
        for (int i = 0; i < hints.size(); i++) {
            if (i > 0) out.add(Span.styled("    ", Style.create()));
            var h = hints.get(i);
            out.add(Span.styled(" " + h.key + " ",
                Style.create().fg(Theme.ignition).bg(Theme.surface).bold()));
            out.add(Span.styled("  " + h.label, Styles.hint()));
        }
        return out;
    }
}
