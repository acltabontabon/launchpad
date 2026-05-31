package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
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
public class TaskInputView implements View {

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),  // top spacer
                Constraint.length(3),  // heading + subhead
                Constraint.min(0)      // task card
            )
            .split(area);

        renderHeading(frame, rows.get(1));
        renderInputCard(frame, rows.get(2), state);
    }

    private void renderHeading(Frame frame, Rect area) {
        var content = Text.from(
            Line.from(Span.styled("  Describe the task", Styles.heading())),
            Line.from(Span.styled(
                "  Be vague if you like - the local model will interview you to refine it.",
                Styles.caption()))
        );
        var p = Paragraph.builder().text(content).build();
        frame.renderWidget(p, area);
    }

    private void renderInputCard(Frame frame, Rect area, AppState state) {
        var card = Card.of("new task").active(true).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var lines = new ArrayList<Line>();
        var buffer = state.task.description;
        var bufLines = buffer.isEmpty() ? new String[]{""} : buffer.split("\n", -1);
        for (int i = 0; i < bufLines.length; i++) {
            var text = bufLines[i];
            if (i == bufLines.length - 1) {
                lines.add(Line.from(
                    Span.styled(text, Styles.code()),
                    Span.styled("█", Style.create().fg(Theme.fuel))
                ));
            } else {
                lines.add(Line.from(Span.styled(text, Styles.code())));
            }
        }
        var p = Paragraph.builder().text(Text.from(lines.toArray(new Line[0]))).build();
        frame.renderWidget(p, inner);
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        return List.of(
            new KeyHint("enter", "newline"),
            new KeyHint("tab", "start interview"),
            new KeyHint("esc", "cancel")
        );
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.ESCAPE)) {
            state.resetTaskFlow();
            state.nav.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        if (key.isKey(KeyCode.TAB)) {
            if (state.task.description.trim().isEmpty()) return true;
            state.nav.currentScreen = AppState.Screen.TASK_INTERVIEW;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            state.task.description = state.task.description + "\n";
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            if (!state.task.description.isEmpty()) {
                state.task.description =
                    state.task.description.substring(0, state.task.description.length() - 1);
            }
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            state.task.description = state.task.description + key.character();
            return true;
        }
        return false;
    }
}
