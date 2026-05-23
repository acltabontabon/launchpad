package com.acltabontabon.launchpad.tui.view;

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

    private static final int FIELD_BASE_URL = 0;
    private static final int FIELD_MODEL = 1;
    private static final int FIELD_REMOTE_STANDARDS = 2;
    private static final int FIELD_CONNECT_ACTION = 3;
    private static final int FIELD_COUNT = 4;

    private static final int BADGE_WIDTH = "[not found]".length();

    private final LaunchpadSettings settings;
    private final ClientRegistry clientRegistry;
    private final SnippetFactory snippetFactory;
    private final McpConfigWriter mcpWriter;

    public SettingsView(LaunchpadSettings settings,
                        ClientRegistry clientRegistry,
                        SnippetFactory snippetFactory,
                        McpConfigWriter mcpWriter) {
        this.settings = settings;
        this.clientRegistry = clientRegistry;
        this.snippetFactory = snippetFactory;
        this.mcpWriter = mcpWriter;
    }

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        switch (state.settingsMode) {
            case FIELDS -> renderFields(frame, area, state);
            case MCP_PICKER -> renderPicker(frame, area, state);
            case MCP_CONFIRM -> renderConfirm(frame, area, state);
            case MCP_RESULT -> renderResult(frame, area, state);
        }
    }

    private void renderFields(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),
                Constraint.length(3),
                Constraint.length(3),
                Constraint.length(1),
                Constraint.length(3),
                Constraint.length(1),
                Constraint.length(3),
                Constraint.length(1),
                Constraint.length(3),
                Constraint.length(2),
                Constraint.min(0)
            )
            .split(area);

        renderHeading(frame, rows.get(1));

        renderField(frame, rows.get(2), "Ollama base URL",
            state.settingsBaseUrlInput, state.settingsFocusIndex == FIELD_BASE_URL);
        renderField(frame, rows.get(4), "Model",
            state.settingsModelInput, state.settingsFocusIndex == FIELD_MODEL);
        renderField(frame, rows.get(6), "Remote standards URL  " + Icons.SEP + "  optional",
            state.settingsRemoteStandardsUrlInput, state.settingsFocusIndex == FIELD_REMOTE_STANDARDS);
        renderActionRow(frame, rows.get(8),
            "Connect to AI tool",
            "wire Launchpad into Claude Desktop / Code / Cursor (MCP)",
            state.settingsFocusIndex == FIELD_CONNECT_ACTION);

        if (state.settingsErrorMessage != null) {
            renderError(frame, rows.get(9), state.settingsErrorMessage);
        }
    }

    private void renderPicker(Frame frame, Rect area, AppState state) {
        var clients = state.mcpClients.get();
        var selected = state.mcpSelected.get();
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
            boolean isCursor = i == state.mcpSelectionIndex;
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

        if (state.settingsErrorMessage != null) {
            renderError(frame, rows.get(3), state.settingsErrorMessage);
        }
    }

    private void renderConfirm(Frame frame, Rect area, AppState state) {
        var clients = state.mcpClients.get();
        var selected = state.mcpSelected.get();
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
        var reports = state.mcpReports.get();

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
                state.mcpBackupDir == null
                    ? "  No files modified."
                    : "  Backups: " + state.mcpBackupDir,
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

    private static void renderField(Frame frame, Rect area, String label, String value, boolean focused) {
        var fieldArea = centeredColumn(area, 80);
        var card = Card.of(label).active(focused).build();
        var inner = card.inner(fieldArea);
        frame.renderWidget(card, fieldArea);

        var line = focused
            ? Line.from(
                Span.styled(value, Styles.code()),
                Span.styled("█", Style.create().fg(Theme.fuel)))
            : Line.from(Span.styled(value, Styles.muted()));
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), inner);
    }

    private static void renderActionRow(Frame frame, Rect area, String label, String hint, boolean focused) {
        var fieldArea = centeredColumn(area, 80);
        var card = Card.of(label).active(focused).build();
        var inner = card.inner(fieldArea);
        frame.renderWidget(card, fieldArea);

        var line = Line.from(
            Span.styled(Icons.ARROW_RIGHT + "  ", focused ? Styles.focus() : Styles.muted()),
            Span.styled("press enter  ", focused ? Styles.focus() : Styles.muted()),
            Span.styled(Icons.SEP + "  ", Styles.muted()),
            Span.styled(hint, Styles.dim())
        );
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), inner);
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
        return switch (state.settingsMode) {
            case FIELDS -> List.of(
                new KeyHint("tab", "next field"),
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
        return switch (state.settingsMode) {
            case FIELDS -> handleFields(key, state);
            case MCP_PICKER -> handlePicker(key, state);
            case MCP_CONFIRM -> handleConfirm(key, state);
            case MCP_RESULT -> handleResult(key, state);
        };
    }

    private boolean handleFields(KeyEvent key, AppState state) {
        if (key.isKey(KeyCode.ESCAPE)) {
            state.settingsErrorMessage = null;
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            if (state.settingsFocusIndex == FIELD_CONNECT_ACTION) {
                openPicker(state);
                return true;
            }
            return save(state);
        }
        if (key.isKey(KeyCode.TAB)) {
            state.settingsFocusIndex = (state.settingsFocusIndex + 1) % FIELD_COUNT;
            return true;
        }
        if (state.settingsFocusIndex == FIELD_CONNECT_ACTION) {
            // Action row swallows character input so stray keys don't accumulate.
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

    private boolean handlePicker(KeyEvent key, AppState state) {
        var clients = state.mcpClients.get();
        if (key.isKey(KeyCode.ESCAPE)) {
            state.settingsMode = SettingsMode.FIELDS;
            state.settingsErrorMessage = null;
            return true;
        }
        if (key.isKey(KeyCode.UP)) {
            state.mcpSelectionIndex = Math.floorMod(state.mcpSelectionIndex - 1, Math.max(clients.size(), 1));
            return true;
        }
        if (key.isKey(KeyCode.DOWN)) {
            state.mcpSelectionIndex = Math.floorMod(state.mcpSelectionIndex + 1, Math.max(clients.size(), 1));
            return true;
        }
        if (key.code() == KeyCode.CHAR && key.character() == ' ') {
            toggleSelection(state, clients);
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            var selected = state.mcpSelected.get();
            if (selected.isEmpty()) {
                state.settingsErrorMessage = "Pick at least one client (space to toggle)";
                return true;
            }
            state.settingsErrorMessage = null;
            state.settingsMode = SettingsMode.MCP_CONFIRM;
            return true;
        }
        return false;
    }

    private boolean handleConfirm(KeyEvent key, AppState state) {
        if (key.isKey(KeyCode.ESCAPE)) {
            state.settingsMode = SettingsMode.MCP_PICKER;
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
            state.settingsMode = SettingsMode.FIELDS;
            state.mcpBackupDir = null;
            return true;
        }
        return false;
    }

    private void openPicker(AppState state) {
        state.mcpClients.set(clientRegistry.discover());
        state.mcpSelected.set(new HashSet<>());
        state.mcpReports.set(new ArrayList<>());
        state.mcpSelectionIndex = 0;
        state.settingsErrorMessage = null;
        state.mcpBackupDir = null;
        state.settingsMode = SettingsMode.MCP_PICKER;
    }

    private static void toggleSelection(AppState state, List<AiClient> clients) {
        if (clients.isEmpty()) return;
        var idx = Math.min(state.mcpSelectionIndex, clients.size() - 1);
        var client = clients.get(idx);
        if (client.alreadyLinked()) {
            state.settingsErrorMessage = client.displayName()
                + " is already linked - remove the launchpad entry from "
                + client.configPath() + " to re-link";
            return;
        }
        if (!client.detected()) {
            state.settingsErrorMessage = client.displayName() + " is not installed on this machine";
            return;
        }
        state.settingsErrorMessage = null;
        var current = state.mcpSelected.get();
        var next = new HashSet<>(current);
        if (!next.remove(client.id())) next.add(client.id());
        state.mcpSelected.set(next);
    }

    private void runWrite(AppState state) {
        var snippet = snippetFactory.build();
        var clients = state.mcpClients.get();
        var selected = state.mcpSelected.get();
        var picked = new ArrayList<AiClient>();
        for (var c : clients) {
            if (selected.contains(c.id())) picked.add(c);
        }
        var run = mcpWriter.apply(picked, snippet.orElse(null));
        state.mcpReports.set(run.reports());
        state.mcpBackupDir = run.backupDir() == null ? null : run.backupDir().toString();
        state.settingsMode = SettingsMode.MCP_RESULT;
    }

    private boolean save(AppState state) {
        var url = state.settingsBaseUrlInput.trim();
        var model = state.settingsModelInput.trim();
        var remoteUrl = state.settingsRemoteStandardsUrlInput.trim();
        if (url.isEmpty() || model.isEmpty()) {
            state.settingsErrorMessage = "Ollama base URL and model cannot be empty";
            return true;
        }
        try {
            settings.update(url, model, remoteUrl);
        } catch (IOException e) {
            state.settingsErrorMessage = "Could not save: " + e.getMessage();
            return true;
        }
        state.settingsErrorMessage = null;
        state.healthCheckRequested = true;
        state.remoteStandardsCheckRequested = true;
        state.currentScreen = AppState.Screen.WELCOME;
        return true;
    }

    private static void appendChar(AppState state, char c) {
        switch (state.settingsFocusIndex) {
            case FIELD_BASE_URL -> state.settingsBaseUrlInput = state.settingsBaseUrlInput + c;
            case FIELD_MODEL -> state.settingsModelInput = state.settingsModelInput + c;
            case FIELD_REMOTE_STANDARDS ->
                state.settingsRemoteStandardsUrlInput = state.settingsRemoteStandardsUrlInput + c;
        }
    }

    private static void popChar(AppState state) {
        switch (state.settingsFocusIndex) {
            case FIELD_BASE_URL -> state.settingsBaseUrlInput = chop(state.settingsBaseUrlInput);
            case FIELD_MODEL -> state.settingsModelInput = chop(state.settingsModelInput);
            case FIELD_REMOTE_STANDARDS ->
                state.settingsRemoteStandardsUrlInput = chop(state.settingsRemoteStandardsUrlInput);
        }
    }

    private static String chop(String s) {
        return s.isEmpty() ? s : s.substring(0, s.length() - 1);
    }
}
