package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.command.Command;
import com.acltabontabon.launchpad.tui.command.CommandPalette;
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
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class HelpView implements View {

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),
                Constraint.length(2),
                Constraint.min(0)
            )
            .split(area);

        var heading = Paragraph.builder()
            .text(Text.from(Line.from(Span.styled("  Help", Styles.heading()))))
            .build();
        frame.renderWidget(heading, rows.get(1));

        var lines = new ArrayList<Line>();
        lines.add(blank());
        lines.add(sectionLabel("Commands"));
        lines.add(blank());
        for (Command c : CommandPalette.ALL) {
            lines.add(commandLine(c));
        }
        lines.add(blank());
        lines.add(sectionLabel("Tips"));
        lines.add(blank());
        lines.add(tipLine("Press / on the Welcome screen to open the command palette."));
        lines.add(tipLine("Pick a command with arrow keys, then press enter to run it."));
        lines.add(tipLine("Press esc at any time to back out of a screen or clear input."));

        int cardWidth = Math.min(area.width() - 4, 72);
        int cardHeight = Math.min(rows.get(2).height(), lines.size() + 3);
        int leftPad = Math.max(0, (rows.get(2).width() - cardWidth) / 2);
        var cardArea = new Rect(rows.get(2).x() + leftPad, rows.get(2).y(), cardWidth, cardHeight);

        var card = Card.of("help").build();
        var inner = card.inner(cardArea);
        frame.renderWidget(card, cardArea);

        var body = Paragraph.builder().text(Text.from(lines.toArray(new Line[0]))).build();
        frame.renderWidget(body, inner);
    }

    private static Line sectionLabel(String text) {
        return Line.from(
            Span.styled("  ", Style.create()),
            Span.styled(text, Style.create().fg(Theme.text).bold())
        );
    }

    private static Line commandLine(Command c) {
        return Line.from(
            Span.styled("  ", Style.create()),
            Span.styled(padRight(c.id(), 12), Style.create().fg(Theme.ignition).bold()),
            Span.styled("  " + c.description(), Styles.muted())
        );
    }

    private static Line tipLine(String text) {
        return Line.from(
            Span.styled("  ", Style.create()),
            Span.styled(text, Styles.caption())
        );
    }

    private static Line blank() {
        return Line.from(Span.styled("", Style.create()));
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        return List.of(
            new KeyHint("esc", "back"),
            new KeyHint("q", "quit")
        );
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;
        if (key.isKey(KeyCode.ESCAPE)) {
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        return false;
    }
}
