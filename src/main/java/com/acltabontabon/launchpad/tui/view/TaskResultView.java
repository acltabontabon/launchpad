package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
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
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

/**
 * Displays the synthesised prompt and the on-disk path it was saved to. The actual
 * finalize call and file write happen on a background thread, kicked off by
 * LaunchpadRunner when the screen becomes active.
 */
@Component
public class TaskResultView implements View {

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private int spinnerFrame = 0;
    // Scroll offset (line index of the top of the visible prompt window).
    private int scrollOffset = 0;

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        spinnerFrame = (spinnerFrame + 1) % SPINNER.length;

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(3),  // header / status
                Constraint.min(0),     // prompt body
                Constraint.length(1)   // hints
            )
            .split(area);

        renderHeader(frame, rows.get(0), state);
        renderPrompt(frame, rows.get(1), state);
        renderHints(frame, rows.get(2), state);
    }

    private void renderHeader(Frame frame, Rect area, AppState state) {
        Style style;
        String text;
        if (state.taskError) {
            style = Style.create().fg(Color.RED).bold();
            text = " ✗  " + (state.taskStatus.get().isEmpty() ? "Something went wrong." : state.taskStatus.get());
        } else if (state.taskThinking) {
            style = Style.create().fg(Color.CYAN);
            var status = state.taskStatus.get().isEmpty() ? "Synthesising prompt..." : state.taskStatus.get();
            text = " " + SPINNER[spinnerFrame] + "  " + status + elapsedSuffix(state);
        } else if (state.taskFinalPrompt.isEmpty()) {
            // Not thinking, not errored, but no content yet - briefly between trigger and stream start.
            style = Style.create().fg(Color.DARK_GRAY);
            text = " ⋯  Preparing...";
        } else if (state.taskSavedPath.isEmpty()) {
            style = Style.create().fg(Color.YELLOW);
            text = " ●  Prompt ready (saving to disk...)";
        } else {
            style = Style.create().fg(Color.GREEN).bold();
            text = " ✓  Saved to " + state.taskSavedPath;
        }

        var header = Paragraph.builder()
            .text(Text.styled(text, style))
            .block(Block.builder()
                .borders(Borders.BOTTOM_ONLY)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(header, area);
    }

    private static String elapsedSuffix(AppState state) {
        long start = state.taskOpStartedAtMs;
        if (start == 0L) return "";
        long sec = (System.currentTimeMillis() - start) / 1000;
        return sec <= 0 ? "" : "  (" + sec + "s)";
    }

    private void renderPrompt(Frame frame, Rect area, AppState state) {
        var content = state.taskFinalPrompt;
        var lines = new ArrayList<Line>();

        if (state.taskError) {
            lines.add(Line.from(Span.styled("",  Style.create())));
            lines.add(Line.from(Span.styled("   " + state.taskStatus.get(),
                Style.create().fg(Color.RED))));
            lines.add(Line.from(Span.styled("",  Style.create())));
            lines.add(Line.from(Span.styled("   Press q to return to Welcome, then try again.",
                Style.create().fg(Color.DARK_GRAY).italic())));
        } else if (content.isEmpty()) {
            lines.add(Line.from(Span.styled("",  Style.create())));
            lines.add(Line.from(Span.styled("   " + SPINNER[spinnerFrame] + "  waiting for the local model to synthesise the final prompt..."
                    + elapsedSuffix(state),
                Style.create().fg(Color.DARK_GRAY).italic())));
        } else {
            for (var raw : content.split("\n", -1)) {
                lines.add(Line.from(Span.styled(" " + raw, styleForMarkdownLine(raw))));
            }
        }

        // Clamp scroll so we don't run past the end.
        int viewportRows = Math.max(1, area.height() - 2);  // minus borders
        int maxScroll = Math.max(0, lines.size() - viewportRows);
        // While the prompt is still streaming, force-pin to the tail so the user
        // watches new tokens appear instead of staring at the now-stale top.
        if (state.taskThinking) scrollOffset = maxScroll;
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        var visible = new ArrayList<Line>();
        int end = Math.min(lines.size(), scrollOffset + viewportRows);
        for (int i = scrollOffset; i < end; i++) visible.add(lines.get(i));

        var box = Paragraph.builder()
            .text(Text.from(visible))
            .overflow(Overflow.WRAP_WORD)
            .block(Block.builder()
                .title(Title.from(Span.styled(" Refined Prompt ",
                    Style.create().fg(Color.GREEN))))
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(Color.GREEN))
                .build())
            .build();
        frame.renderWidget(box, area);
    }

    private static Style styleForMarkdownLine(String line) {
        var trimmed = line.stripLeading();
        if (trimmed.startsWith("## ")) return Style.create().fg(Color.YELLOW).bold();
        if (trimmed.startsWith("### ")) return Style.create().fg(Color.CYAN).bold();
        if (trimmed.startsWith("_Why:_")) return Style.create().fg(Color.DARK_GRAY).italic();
        return Style.create().fg(Color.WHITE);
    }

    private void renderHints(Frame frame, Rect area, AppState state) {
        boolean ready = !state.taskFinalPrompt.isEmpty();
        var hints = Paragraph.builder()
            .text(Text.from(Line.from(
                Span.styled(" ↑↓ ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" scroll  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" PgUp/PgDn ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" jump  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" n ", Style.create().fg(Color.BLACK).bg(ready ? Color.YELLOW : Color.DARK_GRAY)),
                Span.styled(" new task  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" q ", Style.create().fg(Color.BLACK).bg(Color.DARK_GRAY)),
                Span.styled(" welcome", Style.create().fg(Color.DARK_GRAY))
            )))
            .build();
        frame.renderWidget(hints, area);
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.UP)) {
            // Bigger step than 1 since the prompt body uses word-wrap and each
            // "line" in our list may visually span 2-3 terminal rows. Single-line
            // steps look like nothing happened.
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
            scrollOffset = Integer.MAX_VALUE;  // render-side clamp will pin to maxScroll
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
