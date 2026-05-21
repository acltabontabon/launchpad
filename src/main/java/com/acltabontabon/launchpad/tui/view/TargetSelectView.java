package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.template.ContextTarget;
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
import org.springframework.stereotype.Component;

@Component
public class TargetSelectView implements View {

    private static final ContextTarget[] TARGETS = ContextTarget.values();

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(3),   // title
                Constraint.min(0),      // target cards
                Constraint.length(1)    // hints
            )
            .split(area);

        // Title
        var title = Paragraph.builder()
            .text(Text.styled(" Step 2 of 3 - Select Target", Style.create().fg(Color.CYAN).bold()))
            .block(Block.builder()
                .borders(Borders.BOTTOM_ONLY)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(title, rows.get(0));

        // Target cards split horizontally
        var cols = Layout.horizontal()
            .constraints(
                Constraint.percentage(50),
                Constraint.percentage(50)
            )
            .split(rows.get(1));

        for (int i = 0; i < TARGETS.length; i++) {
            renderTargetCard(frame, cols.get(i), TARGETS[i], i == state.targetCursorIndex);
        }

        // Hints
        var hints = Paragraph.builder()
            .text(Text.from(Line.from(
                Span.styled(" ←→ ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" navigate  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" Enter ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" select  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" Esc ", Style.create().fg(Color.BLACK).bg(Color.DARK_GRAY)),
                Span.styled(" back", Style.create().fg(Color.DARK_GRAY))
            )))
            .build();
        frame.renderWidget(hints, rows.get(2));
    }

    private void renderTargetCard(Frame frame, Rect area, ContextTarget target, boolean selected) {
        var borderStyle = selected
            ? Style.create().fg(Color.YELLOW)
            : Style.create().fg(Color.DARK_GRAY);

        var titleStyle = selected
            ? Style.create().fg(Color.YELLOW).bold()
            : Style.create().fg(Color.WHITE);

        var block = Block.builder()
            .title(Title.from(Span.styled(" " + target.displayName + " ", titleStyle)))
            .borders(Borders.ALL)
            .borderStyle(borderStyle)
            .build();

        var inner = block.inner(area);
        frame.renderWidget(block, area);

        // Selected indicator + output file list
        var selectedMark = selected ? "◉  Selected\n\n" : "○  Press ←→ to select\n\n";
        var content = selectedMark + target.description + "\n\n" + outputFileList(target);

        var contentStyle = selected
            ? Style.create().fg(Color.WHITE)
            : Style.create().fg(Color.DARK_GRAY);

        var paragraph = Paragraph.builder()
            .text(Text.styled(content, contentStyle))
            .build();
        frame.renderWidget(paragraph, inner);
    }

    private String outputFileList(ContextTarget target) {
        return switch (target) {
            case CLAUDE -> "Generates:\n  · CLAUDE.md\n  · .ai/index.md\n  · .ai/engineering-rules.md\n  · .ai/stack.md";
            case CURSOR -> "Generates:\n  · .cursorrules\n  · .cursor/rules/engineering.mdc\n  · .cursor/rules/stack.mdc";
        };
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.RIGHT) || key.isChar('l')) {
            state.targetCursorIndex = (state.targetCursorIndex + 1) % TARGETS.length;
            state.selectedTarget = TARGETS[state.targetCursorIndex];
            return true;
        }
        if (key.isKey(KeyCode.LEFT) || key.isChar('h')) {
            state.targetCursorIndex = (state.targetCursorIndex - 1 + TARGETS.length) % TARGETS.length;
            state.selectedTarget = TARGETS[state.targetCursorIndex];
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            state.currentScreen = AppState.Screen.SCANNING;
            return true;
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            state.currentScreen = AppState.Screen.PROJECT_SELECT;
            return true;
        }
        return false;
    }
}
