package com.acltabontabon.launchpad.tui.components;

import com.acltabontabon.launchpad.tui.theme.Icons;
import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Brand mark renderings - the only place in the codebase that knows what
 * Launchpad's wordmark looks like.
 *
 * <ul>
 *   <li>{@link #mark()} - the persistent header lockup: {@code ┃ ◆ LAUNCHPAD}.</li>
 *   <li>{@link #hero()} - the multi-line Welcome hero: ▲-pyramid + LAUNCHPAD
 *       wordmark + tagline, suitable for centering inside a tall rect.</li>
 * </ul>
 */
public final class Brand {

    private Brand() {}

    /**
     * Header lockup. Returns a list of spans the {@link Header} composes with
     * breadcrumb segments.
     */
    public static List<Span> mark() {
        var out = new ArrayList<Span>();
        out.add(Span.styled(" " + Icons.LAUNCH_COLUMN, Style.create().fg(Theme.brand).bold()));
        out.add(Span.styled(" " + Icons.BRAND + " ", Style.create().fg(Theme.fuel).bold()));
        out.add(Span.styled("LAUNCHPAD", Style.create().fg(Theme.text).bold()));
        return out;
    }

    /**
     * Welcome hero block - typography only. The wordmark is the mark; the
     * underline rule gives it weight without competing with it. All lines are
     * centred independently by Paragraph alignment.
     */
    public static Text hero() {
        var wordmarkStyle = Style.create().fg(Theme.text).bold();
        var ruleStyle = Style.create().fg(Theme.brand);
        var subStyle = Style.create().fg(Theme.muted);
        var tagStyle = Styles.caption();

        return Text.from(
            Line.from(Span.styled("", Style.create())),
            Line.from(Span.styled("LAUNCHPAD", wordmarkStyle)),
            Line.from(Span.styled("─────────", ruleStyle)),
            Line.from(Span.styled("Launchpad prepares. Paid agents execute.", subStyle)),
            Line.from(Span.styled("Local-first repo context for AI coding agents", tagStyle))
        );
    }
}
