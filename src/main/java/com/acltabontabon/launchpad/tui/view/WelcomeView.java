package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.ai.LlmProviderStatus;
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

        boolean paletteOpen = state.nav.commandInput.startsWith("/");

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

    // Show the rocket only when both subsystems are non-blocking.
    private static boolean allReady(AppState state) {
        return llmProviderReady(state) && standardsReady(state);
    }

    private static boolean llmProviderReady(AppState state) {
        return state.llmProviderStatus.get().state() == LlmProviderStatus.State.READY;
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
        var llmProvider = state.llmProviderStatus.get();
        var standards = state.remoteStandardsStatus.get();

        var providerName = llmProvider.resolvedProvider() != null
            ? llmProvider.resolvedProvider().displayName()
            : snap.provider() != null ? snap.provider().displayName() : "Local AI";

        int cardWidth = Math.min(area.width() - 4, 80);
        var rows = new java.util.ArrayList<Line>();
        rows.add(blank());
        rows.add(serviceRow(providerName, llmProviderDot(llmProvider), llmProviderLabel(llmProvider), llmProviderDetail(llmProvider, snap), cardWidth));
        rows.add(blank());
        rows.add(serviceRow("Standards", standardsDot(standards), standardsLabelText(standards), standardsDetail(standards), cardWidth));

        int cardHeight = Math.min(area.height(), rows.size() + 2);
        int leftPad = Math.max(0, (area.width() - cardWidth) / 2);
        var cardArea = new Rect(area.x() + leftPad, area.y(), cardWidth, cardHeight);

        var card = Card.of("system check").build();
        var inner = card.inner(cardArea);
        frame.renderWidget(card, cardArea);

        for (int i = 0; i < rows.size() && i < inner.height(); i++) {
            var rowRect = new Rect(inner.x(), inner.y() + i, inner.width(), 1);
            frame.renderWidget(
                Paragraph.builder().text(Text.from(rows.get(i))).build(),
                rowRect);
        }
    }

    private static Line serviceRow(String name, StatusDot.State dot, String label, String detail, int cardWidth) {
        int budget = Math.max(20, cardWidth - 5);
        Style labelStyle = Style.create().fg(dot.color).bold();
        int prefixWidth = 2 + 1 + 2 + 10 + label.length();
        if (detail == null || detail.isBlank()) {
            return Line.from(
                Span.styled("  ", Style.create()),
                Span.styled(dot.glyph, Style.create().fg(dot.color).bold()),
                Span.styled("  ", Style.create()),
                Span.styled(String.format("%-10s", name), Style.create().fg(Theme.text).bold()),
                Span.styled(label, labelStyle)
            );
        }
        String cleanedDetail = fitDetail(detail, Math.max(8, budget - prefixWidth - 5));
        return Line.from(
            Span.styled("  ", Style.create()),
            Span.styled(dot.glyph, Style.create().fg(dot.color).bold()),
            Span.styled("  ", Style.create()),
            Span.styled(String.format("%-10s", name), Style.create().fg(Theme.text).bold()),
            Span.styled(label, labelStyle),
            Span.styled("  " + Icons.SEP + "  ", Styles.muted()),
            Span.styled(cleanedDetail, Styles.caption())
        );
    }

    private static StatusDot.State llmProviderDot(LlmProviderStatus s) {
        return switch (s.state()) {
            case READY -> StatusDot.State.OK;
            case CHECKING -> StatusDot.State.WORKING;
            case DAEMON_DOWN, MODEL_MISSING -> StatusDot.State.ERROR;
        };
    }

    private String llmProviderLabel(LlmProviderStatus s) {
        return switch (s.state()) {
            case READY -> "ready";
            case CHECKING -> "checking " + Spinner.frame(tick / 4);
            case DAEMON_DOWN -> "unreachable";
            case MODEL_MISSING -> "model missing";
        };
    }

    private static String llmProviderDetail(LlmProviderStatus s, LaunchpadSettings.Snapshot snap) {
        var primary = snap.baseUrl() + "  " + Icons.SEP + "  " + snap.model();
        if (s.hint() != null && !s.hint().isBlank()) {
            return primary + "  " + Icons.SEP + "  " + s.hint();
        }
        return primary;
    }

    private static StatusDot.State standardsDot(RemoteStandardsStatus s) {
        return switch (s.state()) {
            case SYNCED -> StatusDot.State.OK;
            case CHECKING -> StatusDot.State.WORKING;
            case STALE_CACHE -> StatusDot.State.WARN;
            case NOT_CONFIGURED -> StatusDot.State.IDLE;
            case ERROR -> StatusDot.State.ERROR;
        };
    }

    private String standardsLabelText(RemoteStandardsStatus s) {
        return switch (s.state()) {
            case SYNCED -> "synced";
            case CHECKING -> "checking " + Spinner.frame(tick / 4);
            case STALE_CACHE -> "offline cache";
            case NOT_CONFIGURED -> "not configured";
            case ERROR -> "fetch failed";
        };
    }

    private static String standardsDetail(RemoteStandardsStatus s) {
        return switch (s.state()) {
            case SYNCED -> null;
            case STALE_CACHE, ERROR -> s.hint() != null && !s.hint().isBlank() ? s.hint() : null;
            case NOT_CONFIGURED -> "set a remote standards URL in /settings to share across projects";
            case CHECKING -> null;
        };
    }

    /**
     * Detail strings can come from external processes (git stderr, model
     * health checks) carrying ANSI escapes, embedded newlines, and arbitrary
     * length. Strip controls, collapse whitespace, clip with an ellipsis.
     */
    static String fitDetail(String text, int budget) {
        if (text == null) return "";
        var clean = text
            .replaceAll("\\[[0-?]*[ -/]*[@-~]", " ")
            .replaceAll("[ -]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (clean.isEmpty()) return "";
        if (clean.length() <= budget) return clean;
        if (budget <= 1) return "…";
        return clean.substring(0, budget - 1) + "…";
    }

    /**
     * Soft recommendation when the configured model is in a known
     * hallucination-prone bucket.
     */
    static String modelTip(String model) {
        if (model == null) return null;
        var m = model.toLowerCase();
        boolean prone = m.startsWith("llama3.2")
            || m.contains("phi3.5:mini") || m.contains("phi3:mini")
            || m.contains("gemma2:2b") || m.contains("gemma:2b")
            || m.endsWith(":1b") || m.endsWith(":2b") || m.endsWith(":3b");
        if (!prone) return null;
        return "tip: " + Icons.WARN + " this model hallucinates file names · try qwen2.5-coder or llama3.1:8b";
    }

    private static Line tipLine(String text, int budget) {
        return Line.from(
            Span.styled("       ", Style.create()),
            Span.styled(fitDetail(text, budget), Style.create().fg(Theme.caution).italic())
        );
    }

    private static Line blank() {
        return Line.from(Span.styled("", Style.create()));
    }

    private void renderCta(Frame frame, Rect area, AppState state) {
        if (!state.nav.welcomeFlashMessage.isEmpty()) {
            var flash = Paragraph.builder()
                .text(Text.styled(state.nav.welcomeFlashMessage, Styles.warning()))
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
        var filtered = CommandPalette.filter(state.nav.commandInput);

        int idWidth = CommandPalette.ALL.stream()
            .mapToInt(c -> c.id().length())
            .max()
            .orElse(0);
        int maxDescLen = CommandPalette.ALL.stream()
            .mapToInt(c -> c.description().length())
            .max()
            .orElse(0);

        int gap = 3;
        int desiredInner = idWidth + gap + maxDescLen + 8;
        int headerWidth = ("commands  " + Icons.SEP + "  " + state.nav.commandInput).length() + 6;
        int cardWidth = Math.min(area.width() - 4, Math.max(desiredInner, headerWidth));
        int cardHeight = Math.min(area.height(), Math.max(6, filtered.size() + 4));
        int leftPad = Math.max(0, (area.width() - cardWidth) / 2);
        var cardArea = new Rect(area.x() + leftPad, area.y(), cardWidth, cardHeight);

        var card = Card.of("commands  " + Icons.SEP + "  " + state.nav.commandInput)
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
        listState.select(Math.min(state.nav.commandCursorIndex, filtered.size() - 1));

        var list = ListWidget.builder()
            .items(items)
            .highlightStyle(Styles.listHighlight())
            .highlightSymbol(Icons.CURSOR + " ")
            .build();
        frame.renderStatefulWidget(list, inner, listState);
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        if (state.nav.commandInput.startsWith("/")) {
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

        var filtered = CommandPalette.filter(state.nav.commandInput);

        if (key.isKey(KeyCode.ENTER)) {
            if (filtered.isEmpty()) return true;
            int idx = Math.min(state.nav.commandCursorIndex, filtered.size() - 1);
            var cmd = filtered.get(idx);
            if (cmd.id().equals("/settings")) {
                var snap = settings.snapshot();
                state.settings.providerInput = snap.provider();
                state.settings.baseUrlInput = snap.baseUrl();
                state.settings.modelInput = snap.model();
                state.settings.apiKeyInput = snap.apiKey() == null ? "" : snap.apiKey();
                state.settings.remoteStandardsUrlInput =
                    snap.remoteStandardsUrl() == null ? "" : snap.remoteStandardsUrl();
                state.settings.focusIndex = 0;
                state.settings.errorMessage = null;
            }
            cmd.action().execute(state, runner);
            state.nav.commandInput = "";
            state.nav.commandCursorIndex = 0;
            return true;
        }

        if (key.isKey(KeyCode.ESCAPE)) {
            state.nav.commandInput = "";
            state.nav.commandCursorIndex = 0;
            state.nav.welcomeFlashMessage = "";
            return true;
        }

        if (key.isKey(KeyCode.UP)) {
            if (!filtered.isEmpty()) {
                state.nav.commandCursorIndex =
                    (state.nav.commandCursorIndex - 1 + filtered.size()) % filtered.size();
            }
            return true;
        }

        if (key.isKey(KeyCode.DOWN)) {
            if (!filtered.isEmpty()) {
                state.nav.commandCursorIndex = (state.nav.commandCursorIndex + 1) % filtered.size();
            }
            return true;
        }

        if (key.isKey(KeyCode.BACKSPACE)) {
            if (!state.nav.commandInput.isEmpty()) {
                state.nav.commandInput =
                    state.nav.commandInput.substring(0, state.nav.commandInput.length() - 1);
                state.nav.commandCursorIndex = 0;
                state.nav.welcomeFlashMessage = "";
            }
            return true;
        }

        if (key.code() == KeyCode.CHAR) {
            // Only accept text input when the palette is already open, or when the
            // keystroke is the '/' that opens it.
            boolean paletteOpen = state.nav.commandInput.startsWith("/");
            if (!paletteOpen && key.character() != '/') {
                return false;
            }
            state.nav.commandInput = state.nav.commandInput + key.character();
            state.nav.commandCursorIndex = 0;
            state.nav.welcomeFlashMessage = "";
            return true;
        }

        return false;
    }
}
