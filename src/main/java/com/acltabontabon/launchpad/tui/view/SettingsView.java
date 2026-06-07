package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.ai.LlmProvider;
import com.acltabontabon.launchpad.ai.LlmProviderStatus;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.mcp.AiClient;
import com.acltabontabon.launchpad.tui.mcp.ClientId;
import com.acltabontabon.launchpad.tui.mcp.ClientRegistry;
import com.acltabontabon.launchpad.tui.mcp.McpConfigWriter;
import com.acltabontabon.launchpad.tui.mcp.McpSnippet;
import com.acltabontabon.launchpad.tui.mcp.SnippetFactory;
import com.acltabontabon.launchpad.tui.mcp.WriteReport;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

@Component
public class SettingsView implements View {

    private static final int FIELD_PROVIDER = 0;
    private static final int FIELD_BASE_URL = 1;
    private static final int FIELD_MODEL = 2;
    private static final int FIELD_API_KEY = 3;
    private static final int FIELD_REMOTE_STANDARDS = 4;
    private static final int FIELD_AUDIT_LLM = 5;
    private static final int FIELD_PROJECTIONS = 6;
    private static final int FIELD_CONNECT_ACTION = 7;
    private static final int FIELD_COUNT = 8;

    private static final int BADGE_WIDTH = "[not found]".length();

    private final LaunchpadSettings settings;
    private final ClientRegistry clientRegistry;
    private final SnippetFactory snippetFactory;
    private final McpConfigWriter mcpWriter;
    private final ProjectionSelectView projectionSelectView;

    public SettingsView(LaunchpadSettings settings,
                        ClientRegistry clientRegistry,
                        SnippetFactory snippetFactory,
                        McpConfigWriter mcpWriter,
                        ProjectionSelectView projectionSelectView) {
        this.settings = settings;
        this.clientRegistry = clientRegistry;
        this.snippetFactory = snippetFactory;
        this.mcpWriter = mcpWriter;
        this.projectionSelectView = projectionSelectView;
    }

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        switch (state.settings.mode) {
            case FIELDS -> renderFields(frame, area, state);
            case MCP_PICKER -> renderPicker(frame, area, state);
            case MCP_CONFIRM -> renderConfirm(frame, area, state);
            case MCP_RESULT -> renderResult(frame, area, state);
        }
    }

    private void renderFields(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),  // 0  top spacer
                Constraint.length(3),  // 1  heading + caption
                Constraint.length(8),  // 2  LLM card (4 rows + vpad + borders)
                Constraint.length(1),  // 3  gap
                Constraint.length(5),  // 4  Standards card
                Constraint.length(1),  // 5  gap
                Constraint.length(5),  // 6  Audit card
                Constraint.length(2),  // 7  gap
                Constraint.length(1),  // 8  Integrations label
                Constraint.length(1),  // 9  AI tools row
                Constraint.length(1),  // 10 MCP clients row
                Constraint.length(1),  // 11 gap
                Constraint.min(0)      // 12 error / fill
            )
            .split(area);

        renderHeading(frame, rows.get(1));
        renderLlmCard(frame, rows.get(2), state);
        renderStandardsCard(frame, rows.get(4), state);
        renderAuditCard(frame, rows.get(6), state);
        renderIntegrationsLabel(frame, rows.get(8));
        renderAiToolsRow(frame, rows.get(9), state);
        renderMcpClientsRow(frame, rows.get(10), state);

        if (state.settings.errorMessage != null) {
            renderError(frame, rows.get(12), state.settings.errorMessage);
        }
    }

    private void renderLlmCard(Frame frame, Rect rowArea, AppState state) {
        var area = centeredColumn(rowArea, CARD_WIDTH);
        boolean groupActive = state.settings.focusIndex == FIELD_PROVIDER
            || state.settings.focusIndex == FIELD_BASE_URL
            || state.settings.focusIndex == FIELD_MODEL
            || state.settings.focusIndex == FIELD_API_KEY;
        var card = Card.of("LLM").active(groupActive).padding(1, 2).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var llm = state.ollamaStatus.get();
        int innerWidth = inner.width();
        var lines = new Line[]{
            providerRow(state, innerWidth),
            baseUrlRow(state, llm, innerWidth),
            modelRow(state, llm, innerWidth),
            apiKeyRow(state, innerWidth)
        };
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), inner);
    }

    private void renderStandardsCard(Frame frame, Rect rowArea, AppState state) {
        var area = centeredColumn(rowArea, CARD_WIDTH);
        boolean focused = state.settings.focusIndex == FIELD_REMOTE_STANDARDS;
        var card = Card.of("Standards").active(focused).padding(1, 2).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var line = remoteStandardsRow(state, inner.width());
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), inner);
    }

    private void renderAuditCard(Frame frame, Rect rowArea, AppState state) {
        var area = centeredColumn(rowArea, CARD_WIDTH);
        boolean focused = state.settings.focusIndex == FIELD_AUDIT_LLM;
        var card = Card.of("Audit").active(focused).padding(1, 2).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var line = auditLlmChecksRow(state, inner.width());
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), inner);
    }

    private Line auditLlmChecksRow(AppState state, int innerWidth) {
        boolean focused = state.settings.focusIndex == FIELD_AUDIT_LLM;
        boolean enabled = settings.snapshot().enableLlmChecks();
        String checkbox = enabled ? "[x]" : "[ ]";
        String hint = enabled
            ? "on - LLM rules run (1 call per file, up to 20 per rule)"
            : "off - skip llm-kind rules (deterministic checks still run)";
        var row = new RowBuilder(focused)
            .label("LLM checks")
            .value(checkbox + " ", enabled ? Styles.success() : Styles.muted())
            .value(hint, focused ? Styles.focus() : Styles.dim())
            .right(focused ? " space toggles " : "", focused ? Styles.activeChip() : Style.create());
        return row.build(innerWidth);
    }

    private static void renderIntegrationsLabel(Frame frame, Rect rowArea) {
        var area = centeredColumn(rowArea, CARD_WIDTH);
        var line = Line.from(
            Span.styled("  ", Style.create()),
            Span.styled("Integrations", Styles.subheading())
        );
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), area);
    }

    private void renderAiToolsRow(Frame frame, Rect rowArea, AppState state) {
        var area = centeredColumn(rowArea, CARD_WIDTH);
        boolean focused = state.settings.focusIndex == FIELD_PROJECTIONS;
        String chipText = " " + Icons.ARROW_TARGET + " change ";
        String value = fitValue(projectionsValue(), area.width(), chipText.length(), false);
        var row = new RowBuilder(focused)
            .label("AI tools")
            .value(value, focused ? Styles.focus() : Styles.body())
            .right(chipText, focused ? Styles.activeChip() : Styles.muteChip());
        frame.renderWidget(Paragraph.builder().text(Text.from(row.build(area.width()))).build(), area);
    }

    private void renderMcpClientsRow(Frame frame, Rect rowArea, AppState state) {
        var area = centeredColumn(rowArea, CARD_WIDTH);
        boolean focused = state.settings.focusIndex == FIELD_CONNECT_ACTION;
        String chipText = " " + Icons.ARROW_TARGET + " connect ";
        int rightCols = chipText.length();
        String value = fitValue(mcpClientsValue(state), area.width(), rightCols, false);
        var row = new RowBuilder(focused)
            .label("MCP clients")
            .value(value, mcpClientsValueStyle(state, focused));
        row.right(chipText, focused ? Styles.activeChip() : Styles.muteChip());
        frame.renderWidget(Paragraph.builder().text(Text.from(row.build(area.width()))).build(), area);
    }

    private Line providerRow(AppState state, int innerWidth) {
        boolean focused = state.settings.focusIndex == FIELD_PROVIDER;
        var row = new RowBuilder(focused).label("Provider");
        var providers = LlmProvider.values();
        for (int i = 0; i < providers.length; i++) {
            var p = providers[i];
            boolean isActive = p == state.settings.providerInput;
            Style chipStyle;
            if (isActive) {
                chipStyle = focused ? Styles.activeChip() : Styles.brandChip();
            } else {
                chipStyle = focused ? Styles.muteChip() : Styles.muted();
            }
            row.value(" " + p.slug() + " ", chipStyle);
            if (i < providers.length - 1) row.value(" ", Style.create());
        }
        return row.build(innerWidth);
    }

    private Line baseUrlRow(AppState state, LlmProviderStatus llm, int innerWidth) {
        boolean focused = state.settings.focusIndex == FIELD_BASE_URL;
        var chip = baseUrlChip(llm);
        String value = fitValue(state.settings.baseUrlInput, innerWidth,
            chip != null ? chip.text.length() : 0, focused);
        var row = new RowBuilder(focused)
            .label("Base URL")
            .value(value, valueStyle(focused, value));
        if (focused) row.value("█", Style.create().fg(Theme.fuel));
        if (chip != null) row.right(chip.text, chip.style);
        return row.build(innerWidth);
    }

    private Line modelRow(AppState state, LlmProviderStatus llm, int innerWidth) {
        boolean focused = state.settings.focusIndex == FIELD_MODEL;
        var chip = modelChip(llm);
        String value = fitValue(state.settings.modelInput, innerWidth,
            chip != null ? chip.text.length() : 0, focused);
        var row = new RowBuilder(focused)
            .label("Model")
            .value(value, valueStyle(focused, value));
        if (focused) row.value("█", Style.create().fg(Theme.fuel));
        if (chip != null) row.right(chip.text, chip.style);
        return row.build(innerWidth);
    }

    private Line apiKeyRow(AppState state, int innerWidth) {
        boolean focused = state.settings.focusIndex == FIELD_API_KEY;
        boolean empty = state.settings.apiKeyInput.isEmpty();
        String masked = maskApiKey(state.settings.apiKeyInput);
        var row = new RowBuilder(focused)
            .label("API key")
            .value(masked, valueStyle(focused, masked));
        if (focused) row.value("█", Style.create().fg(Theme.fuel));
        if (empty) row.right(" optional ", Styles.muteChip());
        return row.build(innerWidth);
    }

    private Line remoteStandardsRow(AppState state, int innerWidth) {
        boolean focused = state.settings.focusIndex == FIELD_REMOTE_STANDARDS;
        boolean empty = state.settings.remoteStandardsUrlInput.isEmpty();
        var chip = remoteStandardsChip(state);
        int rightCols = (chip != null ? chip.text.length() : 0)
            + (empty ? " optional ".length() + (chip != null ? 1 : 0) : 0);
        String value = fitValue(state.settings.remoteStandardsUrlInput, innerWidth, rightCols, focused);
        var row = new RowBuilder(focused)
            .label("Remote pack")
            .value(value, valueStyle(focused, value));
        if (focused) row.value("█", Style.create().fg(Theme.fuel));
        if (chip != null) row.right(chip.text, chip.style);
        if (empty) row.right(" optional ", Styles.muteChip());
        return row.build(innerWidth);
    }

    private static Style valueStyle(boolean focused, String value) {
        if (focused) return Styles.code();
        return value.isEmpty() ? Styles.dim() : Styles.body();
    }

    private static Chip baseUrlChip(LlmProviderStatus s) {
        return switch (s.state()) {
            case READY, MODEL_MISSING -> new Chip(" " + Icons.CHECK + " reachable ", Styles.successChip());
            case DAEMON_DOWN -> new Chip(" " + Icons.CROSS + " unreachable ", Styles.dangerChip());
            case CHECKING -> null;
        };
    }

    private static Chip modelChip(LlmProviderStatus s) {
        return switch (s.state()) {
            case READY -> new Chip(" " + Icons.CHECK + " loaded ", Styles.successChip());
            case MODEL_MISSING -> new Chip(" " + Icons.WARN + " not loaded ", Styles.cautionChip());
            case DAEMON_DOWN, CHECKING -> null;
        };
    }

    private static Chip remoteStandardsChip(AppState state) {
        if (state.settings.remoteStandardsUrlInput.isEmpty()) return null;
        return switch (state.remoteStandardsStatus.get().state()) {
            case SYNCED -> new Chip(" " + Icons.CHECK + " synced ", Styles.successChip());
            case STALE_CACHE -> new Chip(" " + Icons.WARN + " offline cache ", Styles.cautionChip());
            case ERROR -> new Chip(" " + Icons.CROSS + " fetch failed ", Styles.dangerChip());
            case CHECKING, NOT_CONFIGURED -> null;
        };
    }

    private String projectionsValue() {
        var snap = settings.snapshot();
        if (snap.projections() == null) return "not picked yet";
        if (snap.projections().isEmpty()) return "AGENTS.md + .ai/* only";
        return String.join(", ", snap.projections());
    }

    private static String mcpClientsValue(AppState state) {
        var clients = state.settings.mcpClients.get();
        if (clients.isEmpty()) return "wire Claude Desktop / Code / Cursor";
        var linked = clients.stream().filter(AiClient::alreadyLinked).map(AiClient::displayName).toList();
        if (linked.isEmpty()) return "no clients linked yet";
        return String.join(", ", linked);
    }

    private static Style mcpClientsValueStyle(AppState state, boolean focused) {
        if (focused) return Styles.focus();
        var clients = state.settings.mcpClients.get();
        if (clients.isEmpty()) return Styles.dim();
        boolean anyLinked = clients.stream().anyMatch(AiClient::alreadyLinked);
        return anyLinked ? Styles.success() : Styles.dim();
    }

    private record Chip(String text, Style style) { }

    private static final int LABEL_WIDTH = 13;
    private static final int CARD_WIDTH = 80;

    /** Tracks span column counts so the right cluster lands flush against the inner-area right edge. */
    private static final class RowBuilder {
        private final boolean focused;
        private final List<Span> left = new ArrayList<>();
        private final List<Span> right = new ArrayList<>();
        private int leftCols;
        private int rightCols;

        RowBuilder(boolean focused) {
            this.focused = focused;
            left.add(Span.styled(focused ? Icons.CURSOR : " ", focused ? Styles.focus() : Styles.muted()));
            left.add(Span.styled("  ", Style.create()));
            leftCols += 3;
        }

        RowBuilder label(String text) {
            String padded = padRight(text, LABEL_WIDTH);
            left.add(Span.styled(padded, Styles.muted()));
            leftCols += padded.length();
            return this;
        }

        RowBuilder value(String text, Style style) {
            left.add(Span.styled(text, style));
            leftCols += text.length();
            return this;
        }

        RowBuilder right(String text, Style style) {
            if (rightCols > 0) {
                right.add(Span.styled(" ", Style.create()));
                rightCols += 1;
            }
            right.add(Span.styled(text, style));
            rightCols += text.length();
            return this;
        }

        Line build(int innerWidth) {
            int pad = Math.max(2, innerWidth - leftCols - rightCols);
            var all = new ArrayList<Span>(left.size() + right.size() + 1);
            all.addAll(left);
            all.add(Span.styled(" ".repeat(pad), Style.create()));
            all.addAll(right);
            return Line.from(all.toArray(new Span[0]));
        }
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /**
     * Fits the value to the row's visible width. When unfocused, keeps the head
     * (scheme/host stays readable); when focused, keeps the tail so the input
     * cursor and the chars the user just typed stay visible.
     */
    private static String fitValue(String value, int innerWidth, int rightCols, boolean focused) {
        int budget = innerWidth - 3 - LABEL_WIDTH - rightCols - 2 - (focused ? 1 : 0);
        int max = Math.max(4, budget);
        if (value.length() <= max) return value;
        if (max <= 1) return "…";
        return focused
            ? "…" + value.substring(value.length() - (max - 1))
            : value.substring(0, max - 1) + "…";
    }

    /** Render the api key as a fixed-length mask so the value never shows on screen. */
    private static String maskApiKey(String value) {
        if (value == null || value.isEmpty()) return "";
        return "*".repeat(Math.min(value.length(), 16));
    }

    private void renderPicker(Frame frame, Rect area, AppState state) {
        var clients = state.settings.mcpClients.get();
        var selected = state.settings.mcpSelected.get();
        int rowCount = Math.max(clients.size(), 1);

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),
                Constraint.length(3),
                Constraint.length(rowCount + 2),
                Constraint.length(2),
                Constraint.min(0)
            )
            .split(area);

        var heading = Text.from(
            Line.from(Span.styled("  Connect to AI tool", Styles.heading())),
            Line.from(Span.styled(
                "  Pick which clients to wire up. Existing files are backed up before writing.",
                Styles.caption()))
        );
        frame.renderWidget(Paragraph.builder().text(heading).build(), rows.get(1));

        var listArea = centeredColumn(rows.get(2), 80);
        var card = Card.of("Detected clients").build();
        var inner = card.inner(listArea);
        frame.renderWidget(card, listArea);

        int nameWidth = clients.stream().mapToInt(c -> c.displayName().length()).max().orElse(0);
        var lines = new ArrayList<Line>(clients.size());
        for (int i = 0; i < clients.size(); i++) {
            var c = clients.get(i);
            boolean isCursor = i == state.settings.mcpSelectionIndex;
            boolean isOn = c.alreadyLinked() || selected.contains(c.id());
            boolean isWritable = c.detected();

            String checkbox = isOn ? "[x] " : "[ ] ";
            String prefix = isCursor ? Icons.CURSOR + " " : "  ";
            String badge = badgeFor(c);

            var checkboxStyle = c.alreadyLinked() ? Styles.success()
                : isOn ? Styles.focus() : Styles.muted();
            var nameStyle = isWritable
                ? (isCursor ? Styles.focus() : Styles.body())
                : Styles.muted();
            var pathStyle = Styles.dim();

            String pathText = "  " + Icons.SEP + "  " + c.configPath();

            lines.add(Line.from(
                Span.styled(prefix, Styles.muted()),
                Span.styled(checkbox, checkboxStyle),
                Span.styled(String.format("%-" + nameWidth + "s", c.displayName()), nameStyle),
                Span.styled("  " + String.format("%-" + BADGE_WIDTH + "s", badge), badgeStyle(c)),
                Span.styled(pathText, pathStyle)
            ));
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.toArray(new Line[0]))).build(), inner);

        if (state.settings.errorMessage != null) {
            renderError(frame, rows.get(3), state.settings.errorMessage);
        }
    }

    private void renderConfirm(Frame frame, Rect area, AppState state) {
        var clients = state.settings.mcpClients.get();
        var selected = state.settings.mcpSelected.get();
        var picked = clients.stream().filter(c -> selected.contains(c.id())).toList();

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),
                Constraint.length(3),
                Constraint.length(picked.size() + 4),
                Constraint.min(0)
            )
            .split(area);

        var heading = Text.from(
            Line.from(Span.styled("  Confirm write", Styles.heading())),
            Line.from(Span.styled(
                "  Existing files will be backed up to ~/.launchpad/backups/<ts>/ before merge.",
                Styles.caption()))
        );
        frame.renderWidget(Paragraph.builder().text(heading).build(), rows.get(1));

        var listArea = centeredColumn(rows.get(2), 80);
        var card = Card.of("Will write " + picked.size() + " file" + (picked.size() == 1 ? "" : "s")).build();
        var inner = card.inner(listArea);
        frame.renderWidget(card, listArea);

        var lines = new ArrayList<Line>();
        for (var c : picked) {
            lines.add(Line.from(
                Span.styled("  " + Icons.ARROW_RIGHT + " ", Styles.muted()),
                Span.styled(c.displayName(), Styles.body()),
                Span.styled("  " + Icons.SEP + "  ", Styles.muted()),
                Span.styled(c.configPath().toString(), Styles.dim())
            ));
        }
        if (lines.isEmpty()) {
            lines.add(Line.from(Span.styled("  (no clients selected)", Styles.muted())));
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.toArray(new Line[0]))).build(), inner);
    }

    private void renderResult(Frame frame, Rect area, AppState state) {
        var reports = state.settings.mcpReports.get();

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),
                Constraint.length(3),
                Constraint.length(reports.size() + 2),
                Constraint.min(0)
            )
            .split(area);

        var heading = Text.from(
            Line.from(Span.styled("  Results", Styles.heading())),
            Line.from(Span.styled(
                state.settings.mcpBackupDir == null
                    ? "  No files modified."
                    : "  Backups: " + state.settings.mcpBackupDir,
                Styles.caption()))
        );
        frame.renderWidget(Paragraph.builder().text(heading).build(), rows.get(1));

        var listArea = centeredColumn(rows.get(2), 80);
        var card = Card.of("Per-client outcome").build();
        var inner = card.inner(listArea);
        frame.renderWidget(card, listArea);

        var lines = new ArrayList<Line>(reports.size());
        for (var r : reports) {
            lines.add(Line.from(
                Span.styled("  " + outcomeIcon(r.outcome()) + "  ", outcomeStyle(r.outcome())),
                Span.styled(r.id().displayName(), Styles.body()),
                Span.styled("  " + Icons.SEP + "  ", Styles.muted()),
                Span.styled(r.detail() == null ? "" : r.detail(), Styles.dim())
            ));
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.toArray(new Line[0]))).build(), inner);
    }

    private static String outcomeIcon(WriteReport.Outcome o) {
        return switch (o) {
            case WRITTEN -> Icons.CHECK;
            case SKIPPED_KEY_EXISTS -> Icons.WARN;
            case ERROR_NOT_OBJECT, ERROR_IO, ERROR_DEV_MODE -> Icons.CROSS;
        };
    }

    private static Style outcomeStyle(WriteReport.Outcome o) {
        return switch (o) {
            case WRITTEN -> Styles.success();
            case SKIPPED_KEY_EXISTS -> Styles.warning();
            case ERROR_NOT_OBJECT, ERROR_IO, ERROR_DEV_MODE -> Styles.error();
        };
    }

    private static String badgeFor(AiClient c) {
        if (c.alreadyLinked()) return "[linked]";
        return c.detected() ? "[detected]" : "[not found]";
    }

    private static Style badgeStyle(AiClient c) {
        if (c.alreadyLinked()) return Styles.success();
        return c.detected() ? Styles.success() : Styles.muted();
    }

    private static void renderHeading(Frame frame, Rect area) {
        var content = Text.from(
            Line.from(Span.styled("  Configure Launchpad", Styles.heading())),
            Line.from(Span.styled(
                "  These persist to ~/.launchpad/config.properties.",
                Styles.caption()))
        );
        frame.renderWidget(Paragraph.builder().text(content).build(), area);
    }

    private static void renderError(Frame frame, Rect area, String message) {
        var fieldArea = centeredColumn(area, 80);
        var line = Line.from(
            Span.styled(" " + Icons.CROSS + "  ", Styles.error()),
            Span.styled(message, Styles.error())
        );
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), fieldArea);
    }

    private static Rect centeredColumn(Rect area, int width) {
        int w = Math.min(area.width() - 4, width);
        int left = Math.max(2, (area.width() - w) / 2);
        return new Rect(area.x() + left, area.y(), w, area.height());
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        return switch (state.settings.mode) {
            case FIELDS -> List.of(
                new KeyHint("↑↓", "next field"),
                new KeyHint("enter", "save / open"),
                new KeyHint("esc", "cancel"));
            case MCP_PICKER -> List.of(
                new KeyHint("↑↓", "move"),
                new KeyHint("space", "toggle"),
                new KeyHint("enter", "continue"),
                new KeyHint("esc", "back"));
            case MCP_CONFIRM -> List.of(
                new KeyHint("enter", "write"),
                new KeyHint("esc", "back"));
            case MCP_RESULT -> List.of(
                new KeyHint("enter", "done"),
                new KeyHint("esc", "done"));
        };
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;
        return switch (state.settings.mode) {
            case FIELDS -> handleFields(key, state);
            case MCP_PICKER -> handlePicker(key, state);
            case MCP_CONFIRM -> handleConfirm(key, state);
            case MCP_RESULT -> handleResult(key, state);
        };
    }

    private boolean handleFields(KeyEvent key, AppState state) {
        if (key.isKey(KeyCode.ESCAPE)) {
            state.settings.errorMessage = null;
            state.nav.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            if (state.settings.focusIndex == FIELD_CONNECT_ACTION) {
                openPicker(state);
                return true;
            }
            if (state.settings.focusIndex == FIELD_PROJECTIONS) {
                state.projectionPickerReturnsToSettings = true;
                projectionSelectView.seedSelection(state);
                state.nav.currentScreen = AppState.Screen.PROJECTION_SELECT;
                return true;
            }
            return save(state);
        }
        if (key.isKey(KeyCode.TAB) || key.isKey(KeyCode.DOWN)) {
            state.settings.focusIndex = (state.settings.focusIndex + 1) % FIELD_COUNT;
            return true;
        }
        if (key.isKey(KeyCode.UP)) {
            state.settings.focusIndex = Math.floorMod(state.settings.focusIndex - 1, FIELD_COUNT);
            return true;
        }
        if (state.settings.focusIndex == FIELD_PROVIDER) {
            if (key.isKey(KeyCode.LEFT)) {
                state.settings.providerInput = cycleProvider(state.settings.providerInput, -1);
                return true;
            }
            if (key.isKey(KeyCode.RIGHT)) {
                state.settings.providerInput = cycleProvider(state.settings.providerInput, 1);
                return true;
            }
            if (key.code() == KeyCode.CHAR && key.character() == ' ') {
                state.settings.providerInput = cycleProvider(state.settings.providerInput, 1);
                return true;
            }
            // Stray keys (letters, backspace) are swallowed so they don't fall
            // through into another field's buffer.
            return true;
        }
        if (state.settings.focusIndex == FIELD_AUDIT_LLM) {
            if (key.code() == KeyCode.CHAR && key.character() == ' ') {
                toggleLlmChecks(state);
            }
            // Other keys swallowed - this row is a toggle, not a text input.
            return true;
        }
        if (state.settings.focusIndex == FIELD_CONNECT_ACTION
            || state.settings.focusIndex == FIELD_PROJECTIONS) {
            // Action rows swallow character input so stray keys don't accumulate.
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            popChar(state);
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            appendChar(state, key.character());
            return true;
        }
        return false;
    }

    private static LlmProvider cycleProvider(LlmProvider current, int direction) {
        var values = LlmProvider.values();
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { idx = i; break; }
        }
        return values[Math.floorMod(idx + direction, values.length)];
    }

    private boolean handlePicker(KeyEvent key, AppState state) {
        var clients = state.settings.mcpClients.get();
        if (key.isKey(KeyCode.ESCAPE)) {
            state.settings.mode = SettingsMode.FIELDS;
            state.settings.errorMessage = null;
            return true;
        }
        if (key.isKey(KeyCode.UP)) {
            state.settings.mcpSelectionIndex =
                Math.floorMod(state.settings.mcpSelectionIndex - 1, Math.max(clients.size(), 1));
            return true;
        }
        if (key.isKey(KeyCode.DOWN)) {
            state.settings.mcpSelectionIndex =
                Math.floorMod(state.settings.mcpSelectionIndex + 1, Math.max(clients.size(), 1));
            return true;
        }
        if (key.code() == KeyCode.CHAR && key.character() == ' ') {
            toggleSelection(state, clients);
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            var selected = state.settings.mcpSelected.get();
            if (selected.isEmpty()) {
                state.settings.errorMessage = "Pick at least one client (space to toggle)";
                return true;
            }
            state.settings.errorMessage = null;
            state.settings.mode = SettingsMode.MCP_CONFIRM;
            return true;
        }
        return false;
    }

    private boolean handleConfirm(KeyEvent key, AppState state) {
        if (key.isKey(KeyCode.ESCAPE)) {
            state.settings.mode = SettingsMode.MCP_PICKER;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            runWrite(state);
            return true;
        }
        return false;
    }

    private boolean handleResult(KeyEvent key, AppState state) {
        if (key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.ESCAPE)) {
            state.settings.mode = SettingsMode.FIELDS;
            state.settings.mcpBackupDir = null;
            return true;
        }
        return false;
    }

    private void openPicker(AppState state) {
        state.settings.mcpClients.set(clientRegistry.discover());
        state.settings.mcpSelected.set(new HashSet<>());
        state.settings.mcpReports.set(new ArrayList<>());
        state.settings.mcpSelectionIndex = 0;
        state.settings.errorMessage = null;
        state.settings.mcpBackupDir = null;
        state.settings.mode = SettingsMode.MCP_PICKER;
    }

    private static void toggleSelection(AppState state, List<AiClient> clients) {
        if (clients.isEmpty()) return;
        var idx = Math.min(state.settings.mcpSelectionIndex, clients.size() - 1);
        var client = clients.get(idx);
        if (client.alreadyLinked()) {
            state.settings.errorMessage = client.displayName()
                + " is already linked - remove the launchpad entry from "
                + client.configPath() + " to re-link";
            return;
        }
        if (!client.detected()) {
            state.settings.errorMessage = client.displayName() + " is not installed on this machine";
            return;
        }
        state.settings.errorMessage = null;
        var current = state.settings.mcpSelected.get();
        var next = new HashSet<>(current);
        if (!next.remove(client.id())) next.add(client.id());
        state.settings.mcpSelected.set(next);
    }

    private void runWrite(AppState state) {
        var snippet = snippetFactory.build();
        var clients = state.settings.mcpClients.get();
        var selected = state.settings.mcpSelected.get();
        var picked = new ArrayList<AiClient>();
        for (var c : clients) {
            if (selected.contains(c.id())) picked.add(c);
        }
        var run = mcpWriter.apply(picked, snippet.orElse(null));
        state.settings.mcpReports.set(run.reports());
        state.settings.mcpBackupDir = run.backupDir() == null ? null : run.backupDir().toString();
        state.settings.mode = SettingsMode.MCP_RESULT;
    }

    private void toggleLlmChecks(AppState state) {
        boolean next = !settings.snapshot().enableLlmChecks();
        try {
            settings.updateAuditFlags(next);
            state.settings.errorMessage = null;
        } catch (IOException e) {
            state.settings.errorMessage = "Could not save: " + e.getMessage();
        }
    }

    private boolean save(AppState state) {
        var url = state.settings.baseUrlInput.trim();
        var model = state.settings.modelInput.trim();
        var apiKey = state.settings.apiKeyInput.trim();
        var remoteUrl = state.settings.remoteStandardsUrlInput.trim();
        if (url.isEmpty() || model.isEmpty()) {
            state.settings.errorMessage = "Base URL and model cannot be empty";
            return true;
        }
        try {
            settings.update(state.settings.providerInput, url, model, apiKey, remoteUrl);
        } catch (IllegalArgumentException e) {
            state.settings.errorMessage = e.getMessage();
            return true;
        } catch (IOException e) {
            state.settings.errorMessage = "Could not save: " + e.getMessage();
            return true;
        }
        state.settings.errorMessage = null;
        state.healthCheckRequested = true;
        state.remoteStandardsCheckRequested = true;
        state.nav.currentScreen = AppState.Screen.WELCOME;
        return true;
    }

    private static void appendChar(AppState state, char c) {
        switch (state.settings.focusIndex) {
            case FIELD_BASE_URL -> state.settings.baseUrlInput = state.settings.baseUrlInput + c;
            case FIELD_MODEL -> state.settings.modelInput = state.settings.modelInput + c;
            case FIELD_API_KEY -> state.settings.apiKeyInput = state.settings.apiKeyInput + c;
            case FIELD_REMOTE_STANDARDS ->
                state.settings.remoteStandardsUrlInput = state.settings.remoteStandardsUrlInput + c;
        }
    }

    private static void popChar(AppState state) {
        switch (state.settings.focusIndex) {
            case FIELD_BASE_URL -> state.settings.baseUrlInput = chop(state.settings.baseUrlInput);
            case FIELD_MODEL -> state.settings.modelInput = chop(state.settings.modelInput);
            case FIELD_API_KEY -> state.settings.apiKeyInput = chop(state.settings.apiKeyInput);
            case FIELD_REMOTE_STANDARDS ->
                state.settings.remoteStandardsUrlInput = chop(state.settings.remoteStandardsUrlInput);
        }
    }

    private static String chop(String s) {
        return s.isEmpty() ? s : s.substring(0, s.length() - 1);
    }
}
