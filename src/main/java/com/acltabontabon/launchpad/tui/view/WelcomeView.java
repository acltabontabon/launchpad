package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.ai.OllamaStatus;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
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
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.springframework.stereotype.Component;

@Component
public class WelcomeView implements View {

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private int spinnerFrame = 0;

    private final LaunchpadSettings settings;

    public WelcomeView(LaunchpadSettings settings) {
        this.settings = settings;
    }

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
        spinnerFrame = (spinnerFrame + 1) % SPINNER.length;

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
                Constraint.length(1),   // ollama status badge
                Constraint.length(1),   // ollama target (baseUrl · model)
                Constraint.length(1),   // ollama status hint
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

        var status = state.ollamaStatus.get();

        // Status badge
        var badge = Paragraph.builder()
            .text(Text.styled(badgeText(status), badgeStyle(status)))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(badge, rows.get(2));

        // Current Ollama target
        var snap = settings.snapshot();
        var target = Paragraph.builder()
            .text(Text.styled(
                snap.baseUrl() + "  ·  " + snap.model(),
                Style.create().fg(Color.DARK_GRAY)
            ))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(target, rows.get(3));

        // Status hint (only when not ready)
        if (status.hint() != null) {
            var hint = Paragraph.builder()
                .text(Text.styled(status.hint(), Style.create().fg(Color.DARK_GRAY)))
                .alignment(Alignment.CENTER)
                .build();
            frame.renderWidget(hint, rows.get(4));
        }

        // Version
        var version = Paragraph.builder()
            .text(Text.styled(
                "v0.1.0  ·  Powered by Ollama + Spring AI",
                Style.create().fg(Color.DARK_GRAY)
            ))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(version, rows.get(6));

        // Prompt
        var prompt = Paragraph.builder()
            .text(Text.styled(promptText(status), Style.create().fg(Color.YELLOW).bold()))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(prompt, rows.get(7));

        // Bottom hint
        var bottomHint = Paragraph.builder()
            .text(Text.styled(bottomHintText(status), Style.create().fg(Color.DARK_GRAY)))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(bottomHint, rows.get(8));
    }

    private String badgeText(OllamaStatus status) {
        var glyph = status.state() == OllamaStatus.State.CHECKING ? SPINNER[spinnerFrame] : "●";
        return glyph + "  " + status.message();
    }

    private static Style badgeStyle(OllamaStatus status) {
        return switch (status.state()) {
            case CHECKING      -> Style.create().fg(Color.DARK_GRAY);
            case READY         -> Style.create().fg(Color.GREEN).bold();
            case DAEMON_DOWN,
                 MODEL_MISSING -> Style.create().fg(Color.RED).bold();
        };
    }

    private static String promptText(OllamaStatus status) {
        return status.isReady()
            ? "[ Press Enter to start ]"
            : "[ Press r to re-check ]";
    }

    private static String bottomHintText(OllamaStatus status) {
        return status.isReady()
            ? "c · configure    q · quit"
            : "r · re-check    c · configure    q · quit";
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        // Redraw on every tick. The Welcome screen needs to animate the spinner
        // while the health check is in flight and reflect status changes the
        // moment the background task completes - simpler than tracking deltas.
        if (event instanceof TickEvent) return true;

        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.ENTER) && state.ollamaStatus.get().isReady()) {
            state.currentScreen = AppState.Screen.PROJECT_SELECT;
            return true;
        }

        if (key.isChar('r')) {
            state.healthCheckRequested = true;
            return true;
        }

        if (key.isChar('c')) {
            var snap = settings.snapshot();
            state.settingsBaseUrlInput = snap.baseUrl();
            state.settingsModelInput = snap.model();
            state.settingsFocusIndex = 0;
            state.settingsErrorMessage = null;
            state.currentScreen = AppState.Screen.SETTINGS;
            return true;
        }

        return false;
    }
}
