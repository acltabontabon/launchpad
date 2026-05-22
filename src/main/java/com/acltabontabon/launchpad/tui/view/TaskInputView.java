package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
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
 * Multi-line text input for the user's initial task description. Enter inserts a
 * newline (this is intentionally a notepad, not a single-line submit). Tab confirms
 * and transitions to TASK_INTERVIEW; Esc cancels back to Welcome.
 */
@Component
public class TaskInputView implements View {

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),  // intro
                Constraint.min(0),     // input box
                Constraint.length(1)   // hints
            )
            .split(area);

        var intro = Paragraph.builder()
            .text(Text.from(Line.from(
                Span.styled(" Describe the task you want to hand off to Claude / Cursor.",
                    Style.create().fg(Color.WHITE)),
                Span.styled("  Be vague if you like - the local model will interview you.",
                    Style.create().fg(Color.DARK_GRAY).italic())
            )))
            .build();
        frame.renderWidget(intro, rows.get(0));

        var lines = new ArrayList<Line>();
        var buffer = state.taskDescription;
        var bufLines = buffer.isEmpty() ? new String[]{""} : buffer.split("\n", -1);
        for (int i = 0; i < bufLines.length; i++) {
            var text = bufLines[i];
            if (i == bufLines.length - 1) {
                lines.add(Line.from(
                    Span.styled(" " + text, Style.create().fg(Color.WHITE)),
                    Span.styled("█", Style.create().fg(Color.WHITE))
                ));
            } else {
                lines.add(Line.from(Span.styled(" " + text, Style.create().fg(Color.WHITE))));
            }
        }

        var inputBox = Paragraph.builder()
            .text(Text.from(lines))
            .block(Block.builder()
                .title(Title.from(Span.styled(" New Task ", Style.create().fg(Color.YELLOW))))
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(Color.YELLOW))
                .build())
            .build();
        frame.renderWidget(inputBox, rows.get(1));

        var hints = Paragraph.builder()
            .text(Text.from(Line.from(
                Span.styled(" Enter ", Style.create().fg(Color.BLACK).bg(Color.DARK_GRAY)),
                Span.styled(" newline  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" Tab ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" start interview  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" Esc ", Style.create().fg(Color.BLACK).bg(Color.DARK_GRAY)),
                Span.styled(" cancel", Style.create().fg(Color.DARK_GRAY))
            )))
            .build();
        frame.renderWidget(hints, rows.get(2));
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.ESCAPE)) {
            state.resetTaskFlow();
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        if (key.isKey(KeyCode.TAB)) {
            if (state.taskDescription.trim().isEmpty()) return true;
            state.currentScreen = AppState.Screen.TASK_INTERVIEW;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            state.taskDescription = state.taskDescription + "\n";
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            if (!state.taskDescription.isEmpty()) {
                state.taskDescription =
                    state.taskDescription.substring(0, state.taskDescription.length() - 1);
            }
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            state.taskDescription = state.taskDescription + key.character();
            return true;
        }
        return false;
    }
}
