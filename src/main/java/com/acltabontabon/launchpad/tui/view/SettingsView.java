package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
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

import java.io.IOException;
import java.util.List;

@Component
public class SettingsView implements View {

    private static final int FIELD_BASE_URL = 0;
    private static final int FIELD_MODEL = 1;
    private static final int FIELD_REMOTE_STANDARDS = 2;
    private static final int FIELD_COUNT = 3;

    private final LaunchpadSettings settings;

    public SettingsView(LaunchpadSettings settings) {
        this.settings = settings;
    }

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),  // top spacer
                Constraint.length(3),  // heading + subhead
                Constraint.length(3),  // base URL
                Constraint.length(1),  // gap
                Constraint.length(3),  // model
                Constraint.length(1),  // gap
                Constraint.length(3),  // remote standards
                Constraint.length(2),  // error
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

        if (state.settingsErrorMessage != null) {
            renderError(frame, rows.get(7), state.settingsErrorMessage);
        }
    }

    private static void renderHeading(Frame frame, Rect area) {
        var content = Text.from(
            Line.from(Span.styled("  Configure Launchpad", Styles.heading())),
            Line.from(Span.styled(
                "  These persist to ~/.launchpad/config.properties.",
                Styles.caption()))
        );
        var p = Paragraph.builder().text(content).build();
        frame.renderWidget(p, area);
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
        var p = Paragraph.builder().text(Text.from(line)).build();
        frame.renderWidget(p, inner);
    }

    private static void renderError(Frame frame, Rect area, String message) {
        var fieldArea = centeredColumn(area, 80);
        var line = Line.from(
            Span.styled(" " + Icons.CROSS + "  ", Styles.error()),
            Span.styled(message, Styles.error())
        );
        var p = Paragraph.builder().text(Text.from(line)).build();
        frame.renderWidget(p, fieldArea);
    }

    private static Rect centeredColumn(Rect area, int width) {
        int w = Math.min(area.width() - 4, width);
        int left = Math.max(2, (area.width() - w) / 2);
        return new Rect(area.x() + left, area.y(), w, area.height());
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        return List.of(
            new KeyHint("tab", "next field"),
            new KeyHint("enter", "save"),
            new KeyHint("esc", "cancel")
        );
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.ESCAPE)) {
            state.settingsErrorMessage = null;
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }

        if (key.isKey(KeyCode.ENTER)) {
            return save(state);
        }

        if (key.isKey(KeyCode.TAB)) {
            state.settingsFocusIndex = (state.settingsFocusIndex + 1) % FIELD_COUNT;
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
