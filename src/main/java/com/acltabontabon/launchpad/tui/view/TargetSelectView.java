package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.template.ContextTarget;
import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.theme.Icons;
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

import java.util.List;

@Component
public class TargetSelectView implements View {

    private static final ContextTarget[] TARGETS = ContextTarget.values();

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),  // top spacer
                Constraint.length(3),  // heading + subhead
                Constraint.min(0)      // cards
            )
            .split(area);

        renderHeading(frame, rows.get(1));

        // Two cards side-by-side with a 1-col gutter for breathing room.
        var cols = Layout.horizontal()
            .constraints(
                Constraint.length(2),         // left margin
                Constraint.percentage(50),
                Constraint.length(2),         // gutter
                Constraint.percentage(50),
                Constraint.length(2)          // right margin
            )
            .split(rows.get(2));

        renderTargetCard(frame, cols.get(1), TARGETS[0], state.targetCursorIndex == 0);
        renderTargetCard(frame, cols.get(3), TARGETS[1], state.targetCursorIndex == 1);
    }

    private void renderHeading(Frame frame, Rect area) {
        var content = Text.from(
            Line.from(Span.styled("  Pick a target", Styles.heading())),
            Line.from(Span.styled(
                "  Choose which AI assistant the context files will be generated for.",
                Styles.caption()))
        );
        var paragraph = Paragraph.builder().text(content).build();
        frame.renderWidget(paragraph, area);
    }

    private void renderTargetCard(Frame frame, Rect area, ContextTarget target, boolean selected) {
        var card = Card.of(target.displayName).active(selected).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var lines = new java.util.ArrayList<Line>();
        lines.add(Line.from(Span.styled("", Style.create())));

        // Selection marker line
        if (selected) {
            lines.add(Line.from(
                Span.styled(Icons.BRAND, Style.create().fg(Theme.fuel).bold()),
                Span.styled("  Selected", Style.create().fg(Theme.fuel).bold())
            ));
        } else {
            lines.add(Line.from(
                Span.styled(Icons.DOT_EMPTY, Styles.dim()),
                Span.styled("  " + Icons.SWAP + " to switch", Styles.dim())
            ));
        }
        lines.add(Line.from(Span.styled("", Style.create())));

        // Description
        for (var descLine : wrap(target.description, inner.width())) {
            lines.add(Line.from(Span.styled(descLine,
                selected ? Styles.body() : Styles.muted())));
        }
        lines.add(Line.from(Span.styled("", Style.create())));

        // Outputs label
        lines.add(Line.from(Span.styled("Outputs", Styles.subheading())));
        for (var path : outputPaths(target)) {
            lines.add(Line.from(
                Span.styled(" " + Icons.BULLET + "  ", Styles.dim()),
                Span.styled(path, selected ? Styles.code() : Styles.muted())
            ));
        }

        var paragraph = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(paragraph, inner);
    }

    private static List<String> outputPaths(ContextTarget target) {
        return switch (target) {
            case CLAUDE -> List.of(
                "CLAUDE.md",
                ".ai/index.md",
                ".ai/engineering-rules.md",
                ".ai/stack.md",
                ".claude/skills/<id>/SKILL.md"
            );
            case CURSOR -> List.of(
                ".cursorrules",
                ".cursor/rules/engineering.mdc",
                ".cursor/rules/skills.mdc",
                ".cursor/rules/stack.mdc"
            );
        };
    }

    private static List<String> wrap(String text, int width) {
        var out = new java.util.ArrayList<String>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        var words = text.split("\\s+");
        var current = new StringBuilder();
        for (var w : words) {
            if (current.length() == 0) {
                current.append(w);
            } else if (current.length() + 1 + w.length() <= width) {
                current.append(' ').append(w);
            } else {
                out.add(current.toString());
                current.setLength(0);
                current.append(w);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        return List.of(
            new KeyHint(Icons.SWAP, "switch"),
            new KeyHint("enter", "select"),
            new KeyHint("esc", "back")
        );
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
