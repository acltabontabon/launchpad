package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import dev.tamboui.layout.Alignment;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.springframework.stereotype.Component;

@Component
public class WelcomeView implements View {

    private static final String ASCII_LOGO = """
         ██╗      █████╗ ██╗   ██╗███╗   ██╗ ██████╗██╗  ██╗██████╗  █████╗ ██████╗
         ██║     ██╔══██╗██║   ██║████╗  ██║██╔════╝██║  ██║██╔══██╗██╔══██╗██╔══██╗
         ██║     ███████║██║   ██║██╔██╗ ██║██║     ███████║██████╔╝███████║██║  ██║
         ██║     ██╔══██║██║   ██║██║╚██╗██║██║     ██╔══██║██╔═══╝ ██╔══██║██║  ██║
         ███████╗██║  ██║╚██████╔╝██║ ╚████║╚██████╗██║  ██║██║     ██║  ██║██████╔╝
         ╚══════╝╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═══╝ ╚═════╝╚═╝  ╚═╝╚═╝     ╚═╝  ╚═╝╚═════╝
        """;

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var outerBlock = Block.builder()
            .borders(Borders.ALL)
            .borderStyle(Style.create().fg(Color.CYAN))
            .build();

        var innerArea = outerBlock.inner(area);
        frame.renderWidget(outerBlock, area);

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(7),   // logo
                Constraint.length(2),   // tagline
                Constraint.min(0),      // spacer
                Constraint.length(1),   // version
                Constraint.length(1),   // prompt
                Constraint.length(1)    // hint
            )
            .split(innerArea);

        // Logo
        var logo = Paragraph.builder()
            .text(Text.from(ASCII_LOGO))
            .style(Style.create().fg(Color.CYAN).bold())
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(logo, rows.get(0));

        // Tagline
        var tagline = Paragraph.builder()
            .text(Text.styled(
                "AI Context Generator  ·  Save tokens. Ship faster.",
                Style.create().fg(Color.WHITE)
            ))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(tagline, rows.get(1));

        // Version
        var version = Paragraph.builder()
            .text(Text.styled(
                "v0.1.0  ·  Powered by Ollama + Spring AI",
                Style.create().fg(Color.DARK_GRAY)
            ))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(version, rows.get(3));

        // Press enter prompt
        var prompt = Paragraph.builder()
            .text(Text.styled(
                "[ Press Enter to start ]",
                Style.create().fg(Color.YELLOW).bold()
            ))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(prompt, rows.get(4));

        // Quit hint
        var hint = Paragraph.builder()
            .text(Text.styled(
                "q · quit",
                Style.create().fg(Color.DARK_GRAY)
            ))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(hint, rows.get(5));
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (event instanceof KeyEvent key && key.isKey(KeyCode.ENTER)) {
            state.currentScreen = AppState.Screen.PROJECT_SELECT;
            return true;
        }
        return false;
    }
}
