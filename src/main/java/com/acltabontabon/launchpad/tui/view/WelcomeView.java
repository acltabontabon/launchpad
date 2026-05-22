package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.ai.OllamaStatus;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.standards.RemoteStandardsStatus;
import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.command.CommandPalette;
import dev.tamboui.layout.Alignment;
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
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.list.ListItem;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
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
                Constraint.length(1),   // remote standards badge
                Constraint.min(0),      // flexible spacer
                Constraint.length(1),   // version
                Constraint.length(4),   // command dropdown (rendered only when palette is open)
                Constraint.length(1),   // flash message
                Constraint.length(1),   // input bar
                Constraint.length(1)    // bottom hint
            )
            .split(innerArea);

        var logo = Paragraph.builder()
            .text(Text.from(ASCII_LOGO))
            .style(Style.create().fg(Color.CYAN).bold())
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(logo, rows.get(0));

        var tagline = Paragraph.builder()
            .text(Text.styled(
                "AI Context Generator  ·  Save tokens. Ship faster.",
                Style.create().fg(Color.WHITE)
            ))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(tagline, rows.get(1));

        var status = state.ollamaStatus.get();

        var badge = Paragraph.builder()
            .text(Text.styled(badgeText(status), badgeStyle(status)))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(badge, rows.get(2));

        var snap = settings.snapshot();
        var target = Paragraph.builder()
            .text(Text.styled(
                snap.baseUrl() + "  ·  " + snap.model(),
                Style.create().fg(Color.DARK_GRAY)
            ))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(target, rows.get(3));

        if (status.hint() != null) {
            var hint = Paragraph.builder()
                .text(Text.styled(status.hint(), Style.create().fg(Color.DARK_GRAY)))
                .alignment(Alignment.CENTER)
                .build();
            frame.renderWidget(hint, rows.get(4));
        }

        renderStandardsBadge(frame, rows.get(5), state.remoteStandardsStatus.get());

        var version = Paragraph.builder()
            .text(Text.styled(
                "v0.1.0  ·  Powered by Ollama + Spring AI",
                Style.create().fg(Color.DARK_GRAY)
            ))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(version, rows.get(7));

        renderDropdown(frame, rows.get(8), state);
        renderFlash(frame, rows.get(9), state);
        renderInputBar(frame, rows.get(10), state);
        renderBottomHint(frame, rows.get(11), state);
    }

    private void renderStandardsBadge(Frame frame, Rect area, RemoteStandardsStatus status) {
        var glyph = status.state() == RemoteStandardsStatus.State.CHECKING ? SPINNER[spinnerFrame] : "●";
        var badge = Paragraph.builder()
            .text(Text.styled(glyph + "  " + status.message(), standardsBadgeStyle(status)))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(badge, area);
    }

    private static Style standardsBadgeStyle(RemoteStandardsStatus status) {
        return switch (status.state()) {
            case CHECKING, NOT_CONFIGURED -> Style.create().fg(Color.DARK_GRAY);
            case SYNCED                   -> Style.create().fg(Color.GREEN);
            case STALE_CACHE              -> Style.create().fg(Color.YELLOW);
            case ERROR                    -> Style.create().fg(Color.RED);
        };
    }

    private static void renderDropdown(Frame frame, Rect area, AppState state) {
        var filtered = CommandPalette.filter(state.commandInput);
        if (filtered.isEmpty()) return;

        var items = filtered.stream()
            .map(c -> ListItem.from(" " + c.id() + "  -  " + c.description()))
            .toArray(ListItem[]::new);

        var listState = new ListState();
        listState.select(Math.min(state.commandCursorIndex, filtered.size() - 1));

        var list = ListWidget.builder()
            .items(items)
            .highlightStyle(Style.create().fg(Color.BLACK).bg(Color.YELLOW).bold())
            .highlightSymbol("▶ ")
            .build();

        frame.renderStatefulWidget(list, area, listState);
    }

    private static void renderFlash(Frame frame, Rect area, AppState state) {
        if (state.welcomeFlashMessage.isEmpty()) return;
        var flash = Paragraph.builder()
            .text(Text.styled(state.welcomeFlashMessage, Style.create().fg(Color.YELLOW).bold()))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(flash, area);
    }

    private static void renderInputBar(Frame frame, Rect area, AppState state) {
        var inputLine = Line.from(
            Span.styled(" > ", Style.create().fg(Color.YELLOW).bold()),
            Span.styled(state.commandInput, Style.create().fg(Color.WHITE)),
            Span.styled("█", Style.create().fg(Color.WHITE))
        );
        var input = Paragraph.builder()
            .text(Text.from(inputLine))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(input, area);
    }

    private static void renderBottomHint(Frame frame, Rect area, AppState state) {
        var paletteOpen = state.commandInput.startsWith("/");
        var hintText = paletteOpen
            ? "↑↓ navigate  ·  enter run  ·  esc clear"
            : "type / to see commands";
        var hint = Paragraph.builder()
            .text(Text.styled(hintText, Style.create().fg(Color.DARK_GRAY)))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(hint, area);
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

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (event instanceof TickEvent) return true;
        if (!(event instanceof KeyEvent key)) return false;

        var filtered = CommandPalette.filter(state.commandInput);

        if (key.isKey(KeyCode.ENTER)) {
            if (filtered.isEmpty()) return true;
            int idx = Math.min(state.commandCursorIndex, filtered.size() - 1);
            var cmd = filtered.get(idx);
            // The /settings flow needs the current Ollama config seeded into the
            // input fields before SettingsView takes over - same pattern the old
            // 'c' shortcut used.
            if (cmd.id().equals("/settings")) {
                var snap = settings.snapshot();
                state.settingsBaseUrlInput = snap.baseUrl();
                state.settingsModelInput = snap.model();
                state.settingsRemoteStandardsUrlInput =
                    snap.remoteStandardsUrl() == null ? "" : snap.remoteStandardsUrl();
                state.settingsFocusIndex = 0;
                state.settingsErrorMessage = null;
            }
            cmd.action().execute(state, runner);
            state.commandInput = "";
            state.commandCursorIndex = 0;
            return true;
        }

        if (key.isKey(KeyCode.ESCAPE)) {
            state.commandInput = "";
            state.commandCursorIndex = 0;
            state.welcomeFlashMessage = "";
            return true;
        }

        if (key.isKey(KeyCode.UP)) {
            if (!filtered.isEmpty()) {
                state.commandCursorIndex =
                    (state.commandCursorIndex - 1 + filtered.size()) % filtered.size();
            }
            return true;
        }

        if (key.isKey(KeyCode.DOWN)) {
            if (!filtered.isEmpty()) {
                state.commandCursorIndex = (state.commandCursorIndex + 1) % filtered.size();
            }
            return true;
        }

        if (key.isKey(KeyCode.BACKSPACE)) {
            if (!state.commandInput.isEmpty()) {
                state.commandInput =
                    state.commandInput.substring(0, state.commandInput.length() - 1);
                state.commandCursorIndex = 0;
                state.welcomeFlashMessage = "";
            }
            return true;
        }

        if (key.code() == KeyCode.CHAR) {
            state.commandInput = state.commandInput + key.character();
            state.commandCursorIndex = 0;
            state.welcomeFlashMessage = "";
            return true;
        }

        return false;
    }
}
