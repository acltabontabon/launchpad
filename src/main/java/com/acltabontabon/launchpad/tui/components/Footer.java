package com.acltabontabon.launchpad.tui.components;

import com.acltabontabon.launchpad.ai.LlmProviderStatus;
import com.acltabontabon.launchpad.standards.RemoteStandardsStatus;
import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent single-row footer rendered below every view. Hints supplied by
 * the active view appear on the left; system status dots (LLM provider + Standards)
 * appear on the right when present.
 */
public final class Footer {

    private Footer() {}

    public static void render(Frame frame, Rect area, AppState state, List<KeyHint> hints) {
        // While a quit confirmation is pending, the footer's primary job is to
        // warn the user that a second Ctrl-C exits. Suppress regular hints and
        // show the warning in error style instead.
        var leftSpans = state.quitConfirmPending
            ? quitConfirmSpans()
            : KeyHint.render(hints);
        int leftWidth = visibleWidth(leftSpans);

        var rightSpans = statusSpans(state);
        int rightWidth = visibleWidth(rightSpans);

        int pad = Math.max(2, area.width() - leftWidth - rightWidth - 2);

        var all = new ArrayList<Span>();
        all.add(Span.styled("  ", Style.create()));
        all.addAll(leftSpans);
        all.add(Span.styled(" ".repeat(pad), Style.create()));
        all.addAll(rightSpans);

        var widget = Paragraph.builder()
            .text(Text.from(Line.from(all.toArray(new Span[0]))))
            .build();
        frame.renderWidget(widget, area);
    }

    private static List<Span> quitConfirmSpans() {
        return List.of(Span.styled("Press Ctrl-C again to exit  ·  ESC to cancel",
            Styles.error()));
    }

    private static List<Span> statusSpans(AppState state) {
        var out = new ArrayList<Span>();
        var llmProvider = state.llmProviderStatus.get();
        var standards = state.remoteStandardsStatus.get();
        if (llmProvider != null) {
            out.add(StatusDot.dot(llmProviderState(llmProvider)));
            var label = llmProvider.resolvedProvider() == null
                ? " Local AI  "
                : " " + llmProvider.resolvedProvider().displayName() + "  ";
            out.add(Span.styled(label, Styles.dim()));
        }
        if (standards != null) {
            out.add(StatusDot.dot(standardsState(standards)));
            out.add(Span.styled(" Standards", Styles.dim()));
        }
        return out;
    }

    private static StatusDot.State llmProviderState(LlmProviderStatus s) {
        return switch (s.state()) {
            case READY -> StatusDot.State.OK;
            case CHECKING -> StatusDot.State.WORKING;
            case MODEL_MISSING, DAEMON_DOWN -> StatusDot.State.ERROR;
        };
    }

    private static StatusDot.State standardsState(RemoteStandardsStatus s) {
        return switch (s.state()) {
            case SYNCED -> StatusDot.State.OK;
            case CHECKING -> StatusDot.State.WORKING;
            case STALE_CACHE -> StatusDot.State.WARN;
            case NOT_CONFIGURED -> StatusDot.State.IDLE;
            case ERROR -> StatusDot.State.ERROR;
        };
    }

    private static int visibleWidth(List<Span> spans) {
        int w = 0;
        for (var s : spans) {
            var c = s.content();
            if (c != null) w += c.codePointCount(0, c.length());
        }
        return w;
    }

    /** Convenience: builds an immutable hints list inline. */
    public static List<KeyHint> hints(KeyHint... items) {
        return List.of(items);
    }

    /** Convenience for the common branding fallback. */
    public static Span brandSpacer() {
        return Span.styled("  ", Style.create().fg(Theme.subtle));
    }
}
