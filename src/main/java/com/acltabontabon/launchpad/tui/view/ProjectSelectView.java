package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.PathAutocomplete;
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
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class ProjectSelectView implements View {

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(3),  // input box
                Constraint.length(2),  // validation message
                Constraint.min(0),     // spacer
                Constraint.length(1)   // hints
            )
            .split(area);

        // Path input
        var inputBlock = Block.builder()
            .title(Title.from(Span.styled(" Project Path ", Style.create().fg(Color.YELLOW))))
            .borders(Borders.ALL)
            .borderStyle(Style.create().fg(Color.YELLOW))
            .build();

        var inputLine = Line.from(
            Span.styled(" " + state.projectPath, Style.create().fg(Color.WHITE)),
            Span.styled("█", Style.create().fg(Color.WHITE)),
            Span.styled(state.pathSuggestion, Style.create().fg(Color.DARK_GRAY))
        );
        var inputParagraph = Paragraph.builder()
            .text(Text.from(inputLine))
            .block(inputBlock)
            .build();
        frame.renderWidget(inputParagraph, rows.get(0));

        // Validation feedback
        var validationText = validatePath(state.projectPath);
        var validationStyle = validationText.startsWith("✓")
            ? Style.create().fg(Color.GREEN)
            : Style.create().fg(Color.RED);

        var validation = Paragraph.builder()
            .text(Text.styled(" " + validationText, validationStyle))
            .build();
        frame.renderWidget(validation, rows.get(1));

        // Hints
        var hints = Paragraph.builder()
            .text(Text.from(Line.from(
                Span.styled(" Enter ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" confirm  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" Tab ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" autocomplete  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" Esc ", Style.create().fg(Color.BLACK).bg(Color.DARK_GRAY)),
                Span.styled(" back  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" q ", Style.create().fg(Color.BLACK).bg(Color.DARK_GRAY)),
                Span.styled(" quit", Style.create().fg(Color.DARK_GRAY))
            )))
            .build();
        frame.renderWidget(hints, rows.get(3));
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.ENTER)) {
            if (isValidProjectPath(state.projectPath)) {
                state.launchpadAware = Files.isDirectory(
                    Path.of(state.projectPath, ".launchpad", "standards"));
                state.currentScreen = AppState.Screen.TARGET_SELECT;
                return true;
            }
            return false;
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            if (!state.projectPath.isEmpty()) {
                state.projectPath = state.projectPath.substring(0, state.projectPath.length() - 1);
                state.pathSuggestion = PathAutocomplete.suggest(state.projectPath);
            }
            return true;
        }
        if (key.isKey(KeyCode.TAB) || key.isKey(KeyCode.RIGHT)) {
            if (!state.pathSuggestion.isEmpty()) {
                state.projectPath = state.projectPath + state.pathSuggestion + "/";
                state.pathSuggestion = PathAutocomplete.suggest(state.projectPath);
            }
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            state.projectPath = state.projectPath + key.character();
            state.pathSuggestion = PathAutocomplete.suggest(state.projectPath);
            return true;
        }
        return false;
    }

    private String validatePath(String path) {
        if (path.isEmpty()) return "  Enter a project directory path";
        var p = Path.of(path);
        if (!Files.exists(p)) return "✗  Path does not exist";
        if (!Files.isDirectory(p)) return "✗  Path is not a directory";
        return "✓  Valid project directory";
    }

    private boolean isValidProjectPath(String path) {
        if (path.isEmpty()) return false;
        var p = Path.of(path);
        return Files.exists(p) && Files.isDirectory(p);
    }
}
