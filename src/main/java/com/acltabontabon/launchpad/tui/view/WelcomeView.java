package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.ai.OllamaStatus;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.standards.RemoteStandardsStatus;
import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.command.CommandPalette;
import com.acltabontabon.launchpad.tui.components.Brand;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.components.RocketAnimation;
import com.acltabontabon.launchpad.tui.components.Spinner;
import com.acltabontabon.launchpad.tui.components.StatusDot;
import com.acltabontabon.launchpad.tui.theme.Icons;
import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.layout.Alignment;
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
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.list.ListItem;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WelcomeView implements View {

    private final LaunchpadSettings settings;
    private long tick = 0;

    public WelcomeView(LaunchpadSettings settings) {
        this.settings = settings;
    }

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        tick++;

        boolean paletteOpen = state.commandInput.startsWith("/");

        // Vertical rhythm: spacer · hero · gap · main panel · gap · prompt · spacer
        // The "main panel" slot holds either the system-check card or, when the
        // user is typing a slash command, the palette card - never both. This
        // makes the palette a real modal swap, not a z-stacked overlay.
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),    // top spacer
                Constraint.length(5),    // brand hero
                Constraint.length(2),    // gap
                Constraint.min(0),       // main panel (system check OR palette)
                Constraint.length(1),    // CTA line
                Constraint.length(1)     // bottom spacer
            )
            .split(area);

        var hero = Paragraph.builder()
            .text(Brand.hero())
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(hero, rows.get(1));

        if (paletteOpen) {
            renderPalette(frame, rows.get(3), state);
        } else {
            if (allReady(state)) {
                var rocketArea = rows.get(3);
                frame.renderWidget(
                    RocketAnimation.render(tick, rocketArea.height()),
                    rocketArea
                );
            } else {
                renderSystemCheck(frame, rows.get(3), state);
            }
            renderCta(frame, rows.get(4), state);
        }
    }

    // Show the rocket only when both subsystems are non-blocking. The footer
    // already exposes the same two dots, so dropping the system-check card on
    // a clean boot removes redundant chrome - we only surface it when there
    // is an actionable problem the user can read inline.
    private static boolean allReady(AppState state) {
        return ollamaReady(state) && standardsReady(state);
    }

    private static boolean ollamaReady(AppState state) {
        return state.ollamaStatus.get().state() == OllamaStatus.State.READY;
    }

    private static boolean standardsReady(AppState state) {
        // SYNCED / NOT_CONFIGURED / STALE_CACHE all leave the app fully usable;
        // only an active CHECKING phase or an ERROR justifies hiding the rocket.
        var s = state.remoteStandardsStatus.get().state();
        return s != RemoteStandardsStatus.State.CHECKING
            && s != RemoteStandardsStatus.State.ERROR;
    }

    private void renderSystemCheck(Frame frame, Rect area, AppState state) {
        var snap = settings.snapshot();
        var ollama = state.ollamaStatus.get();
        var standards = state.remoteStandardsStatus.get();

        var lines = new java.util.ArrayList<Line>();
        lines.add(blank());

        // Ollama row
        lines.add(ollamaLine(ollama));
        lines.add(detailLine(snap.baseUrl() + "  " + Icons.SEP + "  " + snap.model()));
        if (ollama.hint() != null && !ollama.hint().isBlank()) {
            lines.add(detailLine(ollama.hint()));
        }
        var modelTip = modelTip(snap.model());
        if (modelTip != null) {
            lines.add(tipLine(modelTip));
        }
        lines.add(blank());

        // Standards row
        lines.add(standardsLine(standards));
        var standardsDetail = standardsDetail(standards);
        if (standardsDetail != null) {
            lines.add(detailLine(standardsDetail));
        }

        // Size the card to fit its actual content (+2 for top/bottom border,
        // +1 trailing breathing row) but never exceed the available area.
        int cardWidth = Math.min(area.width() - 4, 64);
        int cardHeight = Math.min(area.height(), lines.size() + 3);
        int leftPad = Math.max(0, (area.width() - cardWidth) / 2);
        var cardArea = new Rect(area.x() + leftPad, area.y(), cardWidth, cardHeight);

        var card = Card.of("system check").build();
        var inner = card.inner(cardArea);
        frame.renderWidget(card, cardArea);

        var body = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(body, inner);
    }

    private Line ollamaLine(OllamaStatus s) {
        var state = switch (s.state()) {
            case READY -> StatusDot.State.OK;
            case CHECKING -> StatusDot.State.WORKING;
            case DAEMON_DOWN, MODEL_MISSING -> StatusDot.State.ERROR;
        };
        var label = switch (s.state()) {
            case READY -> "ready";
            case CHECKING -> "checking " + Spinner.frame(tick / 4);
            case DAEMON_DOWN -> "daemon unreachable";
            case MODEL_MISSING -> "model missing";
        };
        return Line.from(
            Span.styled("  ", Style.create()),
            Span.styled(state.glyph, Style.create().fg(state.color).bold()),
            Span.styled("  Ollama       ", Style.create().fg(Theme.text).bold()),
            Span.styled(label, Style.create().fg(state.color))
        );
    }

    private Line standardsLine(RemoteStandardsStatus s) {
        var st = switch (s.state()) {
            case SYNCED -> StatusDot.State.OK;
            case CHECKING -> StatusDot.State.WORKING;
            case STALE_CACHE -> StatusDot.State.WARN;
            case NOT_CONFIGURED -> StatusDot.State.IDLE;
            case ERROR -> StatusDot.State.ERROR;
        };
        var label = switch (s.state()) {
            case SYNCED -> "synced";
            case CHECKING -> "checking " + Spinner.frame(tick / 4);
            case STALE_CACHE -> "offline cache";
            case NOT_CONFIGURED -> "not configured";
            case ERROR -> "fetch failed";
        };
        return Line.from(
            Span.styled("  ", Style.create()),
            Span.styled(st.glyph, Style.create().fg(st.color).bold()),
            Span.styled("  Standards    ", Style.create().fg(Theme.text).bold()),
            Span.styled(label, Style.create().fg(st.color))
        );
    }

    private static String standardsDetail(RemoteStandardsStatus s) {
        // The badge already says SYNCED / OFFLINE / etc. - the detail line should
        // add NEW information (the why, the cache reason) and not repeat the badge.
        return switch (s.state()) {
            case SYNCED -> null;
            case STALE_CACHE, ERROR -> s.hint() != null && !s.hint().isBlank() ? s.hint() : null;
            case NOT_CONFIGURED -> "set a remote standards URL in /settings to share across projects";
            case CHECKING -> null;
        };
    }

    private static Line detailLine(String text) {
        return Line.from(
            Span.styled("       ", Style.create()),
            Span.styled(text, Styles.caption())
        );
    }

    /**
     * Soft recommendation when the configured model is in a known
     * hallucination-prone bucket. Returns null when the model is fine or
     * we don't recognise it (silent default - no false positives).
     */
    static String modelTip(String model) {
        if (model == null) return null;
        var m = model.toLowerCase();
        // Llama 3.2 default = 3B; phi3/3.5 mini and any sub-4B coder model
        // routinely invents Spring-Boot-shaped files on framework projects.
        boolean prone = m.startsWith("llama3.2")
            || m.contains("phi3.5:mini") || m.contains("phi3:mini")
            || m.contains("gemma2:2b") || m.contains("gemma:2b")
            || m.endsWith(":1b") || m.endsWith(":2b") || m.endsWith(":3b");
        if (!prone) return null;
        return "tip: " + Icons.WARN + " this model hallucinates file names · try qwen2.5-coder or llama3.1:8b";
    }

    private static Line tipLine(String text) {
        return Line.from(
            Span.styled("       ", Style.create()),
            Span.styled(text, Style.create().fg(Theme.caution).italic())
        );
    }

    private static Line blank() {
        return Line.from(Span.styled("", Style.create()));
    }

    private void renderCta(Frame frame, Rect area, AppState state) {
        if (!state.welcomeFlashMessage.isEmpty()) {
            var flash = Paragraph.builder()
                .text(Text.styled(state.welcomeFlashMessage, Styles.warning()))
                .alignment(Alignment.CENTER)
                .build();
            frame.renderWidget(flash, area);
            return;
        }
        var prompt = Line.from(
            Span.styled("Press  ", Styles.muted()),
            Span.styled(" / ", Style.create().fg(Theme.ignition).bg(Theme.surface).bold()),
            Span.styled("  to begin", Styles.muted())
        );
        var paragraph = Paragraph.builder()
            .text(Text.from(prompt))
            .alignment(Alignment.CENTER)
            .build();
        frame.renderWidget(paragraph, area);
    }

    private void renderPalette(Frame frame, Rect area, AppState state) {
        var filtered = CommandPalette.filter(state.commandInput);

        // Pad ids to the longest in the full command set (not just the filtered
        // subset) so descriptions stay column-aligned as the user types. Same
        // reason we size the card off ALL: width should not jitter while typing.
        int idWidth = CommandPalette.ALL.stream()
            .mapToInt(c -> c.id().length())
            .max()
            .orElse(0);
        int maxDescLen = CommandPalette.ALL.stream()
            .mapToInt(c -> c.description().length())
            .max()
            .orElse(0);

        // Card geometry: inner content needs idWidth + gap + description, plus
        // a small margin for the cursor symbol and breathing room. Clamp to the
        // available area so narrow terminals still render gracefully.
        int gap = 3;
        int desiredInner = idWidth + gap + maxDescLen + 4;
        int headerWidth = ("commands  " + Icons.SEP + "  " + state.commandInput).length() + 6;
        int cardWidth = Math.min(area.width() - 4, Math.max(desiredInner, headerWidth));
        int cardHeight = Math.min(area.height(), Math.max(6, filtered.size() + 4));
        int leftPad = Math.max(0, (area.width() - cardWidth) / 2);
        var cardArea = new Rect(area.x() + leftPad, area.y(), cardWidth, cardHeight);

        var card = Card.of("commands  " + Icons.SEP + "  " + state.commandInput)
            .active(true)
            .build();
        var inner = card.inner(cardArea);
        frame.renderWidget(card, cardArea);

        if (filtered.isEmpty()) {
            var empty = Paragraph.builder()
                .text(Text.from(Line.from(Span.styled(
                    "  no matching commands",
                    Styles.caption()))))
                .build();
            frame.renderWidget(empty, inner);
            return;
        }

        String gapStr = " ".repeat(gap);
        var items = filtered.stream()
            .map(c -> ListItem.from(
                Line.from(
                    Span.styled(
                        String.format("%-" + idWidth + "s", c.id()),
                        Style.create().fg(Theme.text).bold()),
                    Span.styled(gapStr + c.description(), Styles.muted())
                )
            ))
            .toArray(ListItem[]::new);

        var listState = new ListState();
        listState.select(Math.min(state.commandCursorIndex, filtered.size() - 1));

        var list = ListWidget.builder()
            .items(items)
            .highlightStyle(Styles.listHighlight())
            .highlightSymbol(Icons.CURSOR + " ")
            .build();
        frame.renderStatefulWidget(list, inner, listState);
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        if (state.commandInput.startsWith("/")) {
            return List.of(
                new KeyHint("↑↓", "navigate"),
                new KeyHint("enter", "run"),
                new KeyHint("esc", "clear")
            );
        }
        return List.of(
            new KeyHint("q", "quit")
        );
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
            // Only accept text input when the palette is already open, or when the
            // keystroke is the '/' that opens it. Without this guard, any stray
            // letter silently accumulates into commandInput and the render check
            // (commandInput.startsWith("/")) never opens the palette - the screen
            // looks frozen because every subsequent keypress mutates an invisible
            // buffer instead of falling through to global handlers (q to quit).
            boolean paletteOpen = state.commandInput.startsWith("/");
            if (!paletteOpen && key.character() != '/') {
                return false;
            }
            state.commandInput = state.commandInput + key.character();
            state.commandCursorIndex = 0;
            state.welcomeFlashMessage = "";
            return true;
        }

        return false;
    }
}
