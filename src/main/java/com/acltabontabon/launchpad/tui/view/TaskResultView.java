package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.components.Spinner;
import com.acltabontabon.launchpad.tui.components.StatusDot;
import com.acltabontabon.launchpad.tui.theme.Icons;
import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TaskResultView implements View {

    private long tick = 0;
    private int scrollOffset = 0;

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        tick++;

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(1),  // top spacer
                Constraint.length(1),  // header status line
                Constraint.length(1),  // gap
                Constraint.min(0)      // prompt card
            )
            .split(area);

        renderHeader(frame, rows.get(1), state);
        renderPrompt(frame, rows.get(3), state);
    }

    private void renderHeader(Frame frame, Rect area, AppState state) {
        Line line;
        if (state.taskError) {
            line = Line.from(
                Span.styled("  " + Icons.CROSS + "  ", Styles.error()),
                Span.styled(state.taskStatus.get().isEmpty()
                    ? "Something went wrong." : state.taskStatus.get(), Styles.error())
            );
        } else if (state.taskThinking) {
            var msg = state.taskStatus.get().isEmpty() ? "synthesising prompt..." : state.taskStatus.get();
            line = Line.from(
                Span.styled("  " + Spinner.frame(tick / 3) + "  ", Styles.focus()),
                Span.styled(msg + elapsedSuffix(state), Styles.caption())
            );
        } else if (state.taskFinalPrompt.isEmpty()) {
            line = Line.from(
                Span.styled("  ⋯  ", Styles.dim()),
                Span.styled("Preparing...", Styles.dim())
            );
        } else if (state.taskSavedPath.isEmpty()) {
            line = StatusDot.of(StatusDot.State.WORKING, "Prompt ready (saving to disk...)");
        } else {
            line = StatusDot.of(StatusDot.State.OK, "Saved to", state.taskSavedPath);
        }
        var widget = Paragraph.builder().text(Text.from(line)).build();
        frame.renderWidget(widget, area);
    }

    private static String elapsedSuffix(AppState state) {
        long start = state.taskOpStartedAtMs;
        if (start == 0L) return "";
        long sec = (System.currentTimeMillis() - start) / 1000;
        return sec <= 0 ? "" : "  " + Icons.SEP + "  " + sec + "s";
    }

    private void renderPrompt(Frame frame, Rect area, AppState state) {
        String bottom = state.taskSavedPath.isEmpty()
            ? ("press y to copy  " + Icons.SEP + "  n for new task")
            : (state.taskSavedPath + "  " + Icons.SEP + "  press n for new task");
        var card = Card.of("refined prompt").bottomTitle(bottom).active(true).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var content = state.taskFinalPrompt;
        var lines = new ArrayList<Line>();

        if (state.taskError) {
            lines.add(blank());
            lines.add(Line.from(Span.styled("  " + state.taskStatus.get(), Styles.error())));
            lines.add(blank());
            lines.add(Line.from(Span.styled("  Press q to return to Welcome, then try again.",
                Styles.caption())));
        } else if (content.isEmpty()) {
            lines.add(blank());
            lines.add(Line.from(
                Span.styled("  " + Spinner.frame(tick / 3) + "  ", Styles.focus()),
                Span.styled("waiting for the local model to synthesise the final prompt..."
                    + elapsedSuffix(state), Styles.caption())
            ));
        } else {
            for (var raw : content.split("\n", -1)) {
                lines.add(Line.from(Span.styled(raw, styleForMarkdownLine(raw))));
            }
        }

        int viewportRows = Math.max(1, inner.height());
        int maxScroll = Math.max(0, lines.size() - viewportRows);
        if (state.taskThinking) scrollOffset = maxScroll;
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        var visible = new ArrayList<Line>();
        int end = Math.min(lines.size(), scrollOffset + viewportRows);
        for (int i = scrollOffset; i < end; i++) visible.add(lines.get(i));

        var widget = Paragraph.builder()
            .text(Text.from(visible.toArray(new Line[0])))
            .overflow(Overflow.WRAP_WORD)
            .build();
        frame.renderWidget(widget, inner);
    }

    private static Line blank() {
        return Line.from(Span.styled("", Style.create()));
    }

    private static Style styleForMarkdownLine(String line) {
        var trimmed = line.stripLeading();
        if (trimmed.startsWith("# ")) return Style.create().fg(Theme.brand).bold();
        if (trimmed.startsWith("## ")) return Style.create().fg(Theme.fuel).bold();
        if (trimmed.startsWith("### ")) return Style.create().fg(Theme.brand).bold();
        if (trimmed.startsWith("_Why:_")) return Styles.caption();
        return Styles.body();
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        boolean ready = !state.taskFinalPrompt.isEmpty();
        var hints = new ArrayList<KeyHint>();
        hints.add(new KeyHint("↑↓", "scroll"));
        hints.add(new KeyHint("pgup/pgdn", "jump"));
        if (ready) hints.add(new KeyHint("n", "new task"));
        hints.add(new KeyHint("q", "welcome"));
        return hints;
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.UP)) {
            scrollOffset = Math.max(0, scrollOffset - 3);
            return true;
        }
        if (key.isKey(KeyCode.DOWN)) {
            scrollOffset += 3;
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            scrollOffset = Math.max(0, scrollOffset - 15);
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            scrollOffset += 15;
            return true;
        }
        if (key.isKey(KeyCode.HOME)) {
            scrollOffset = 0;
            return true;
        }
        if (key.isKey(KeyCode.END)) {
            scrollOffset = Integer.MAX_VALUE;
            return true;
        }
        if (key.isChar('q')) {
            scrollOffset = 0;
            state.resetTaskFlow();
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        if (key.isChar('n') && !state.taskFinalPrompt.isEmpty()) {
            scrollOffset = 0;
            state.resetTaskForReuse();
            state.currentScreen = AppState.Screen.TASK_INPUT;
            return true;
        }
        return false;
    }
}
