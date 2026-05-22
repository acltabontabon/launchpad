package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.tui.AppState;
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
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.io.IOException;
import org.springframework.stereotype.Component;

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
                Constraint.length(3),  // title
                Constraint.length(3),  // base URL input
                Constraint.length(3),  // model input
                Constraint.length(3),  // remote standards URL input
                Constraint.length(2),  // error message
                Constraint.min(0),     // spacer
                Constraint.length(1)   // hints
            )
            .split(area);

        var title = Paragraph.builder()
            .text(Text.styled(" Launchpad Configuration", Style.create().fg(Color.CYAN).bold()))
            .block(Block.builder()
                .borders(Borders.BOTTOM_ONLY)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(title, rows.get(0));

        renderField(frame, rows.get(1), " Ollama Base URL ",
            state.settingsBaseUrlInput, state.settingsFocusIndex == FIELD_BASE_URL);
        renderField(frame, rows.get(2), " Ollama Model ",
            state.settingsModelInput, state.settingsFocusIndex == FIELD_MODEL);
        renderField(frame, rows.get(3), " Remote Standards Git URL (optional) ",
            state.settingsRemoteStandardsUrlInput, state.settingsFocusIndex == FIELD_REMOTE_STANDARDS);

        if (state.settingsErrorMessage != null) {
            var error = Paragraph.builder()
                .text(Text.styled(" ✗  " + state.settingsErrorMessage, Style.create().fg(Color.RED)))
                .build();
            frame.renderWidget(error, rows.get(4));
        }

        var hints = Paragraph.builder()
            .text(Text.from(Line.from(
                Span.styled(" Tab ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" switch field  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" Enter ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" save  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" Esc ", Style.create().fg(Color.BLACK).bg(Color.DARK_GRAY)),
                Span.styled(" cancel", Style.create().fg(Color.DARK_GRAY))
            )))
            .build();
        frame.renderWidget(hints, rows.get(6));
    }

    private static void renderField(Frame frame, Rect area, String label, String value, boolean focused) {
        var borderColor = focused ? Color.YELLOW : Color.DARK_GRAY;
        var labelColor = focused ? Color.YELLOW : Color.DARK_GRAY;
        var display = focused ? value + "█" : value;

        var block = Block.builder()
            .title(Title.from(Span.styled(label, Style.create().fg(labelColor))))
            .borders(Borders.ALL)
            .borderStyle(Style.create().fg(borderColor))
            .build();

        var paragraph = Paragraph.builder()
            .text(Text.styled(" " + display, Style.create().fg(Color.WHITE)))
            .block(block)
            .build();
        frame.renderWidget(paragraph, area);
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
