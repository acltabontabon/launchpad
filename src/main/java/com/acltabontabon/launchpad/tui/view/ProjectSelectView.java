package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.scanner.ProjectSupportDetector;
import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.PathAutocomplete;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.components.StatusDot;
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
import dev.tamboui.widgets.list.ListItem;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class ProjectSelectView implements View {

    private final ProjectSupportDetector projectSupportDetector;

    public ProjectSelectView(ProjectSupportDetector projectSupportDetector) {
        this.projectSupportDetector = projectSupportDetector;
    }

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        boolean hasMatches = !state.pathMatches.isEmpty();
        int matchesCardHeight = hasMatches
            ? Math.min(state.pathMatches.size(), 12) + 2  // rows + top/bottom border
            : 0;

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),                       // top spacer
                Constraint.length(3),                       // heading + subhead
                Constraint.length(3),                       // input card
                Constraint.length(1),                       // gap
                Constraint.length(1),                       // validation
                Constraint.length(1),                       // gap
                Constraint.length(Math.max(0, matchesCardHeight)), // candidates card
                Constraint.min(0)                           // remaining
            )
            .split(area);

        renderHeading(frame, rows.get(1));
        renderInput(frame, rows.get(2), state);
        renderValidation(frame, rows.get(4), state);
        if (hasMatches) {
            renderMatches(frame, rows.get(6), state);
        }
    }

    private void renderHeading(Frame frame, Rect area) {
        var headingArea = centeredColumn(area, 80);
        var heading = Paragraph.builder()
            .text(Text.from(
                Line.from(Span.styled("Choose a project", Styles.heading())),
                Line.from(Span.styled(
                    "Point Launchpad at any directory. We'll scan it before generating.",
                    Styles.caption()))
            ))
            .build();
        frame.renderWidget(heading, headingArea);
    }

    private void renderInput(Frame frame, Rect area, AppState state) {
        var cardArea = centeredColumn(area, 80);
        var card = Card.of("project path").active(true).build();
        var inner = card.inner(cardArea);
        frame.renderWidget(card, cardArea);

        var inputLine = Line.from(
            Span.styled(state.projectPath, Styles.code()),
            Span.styled("█", Style.create().fg(Theme.fuel)),
            Span.styled(state.pathSuggestion, Styles.dim())
        );
        var paragraph = Paragraph.builder().text(Text.from(inputLine)).build();
        frame.renderWidget(paragraph, inner);
    }

    private void renderValidation(Frame frame, Rect area, AppState state) {
        var validationArea = centeredColumn(area, 80);
        Line line;
        if (state.projectGateError != null && !state.projectGateError.isEmpty()) {
            line = StatusDot.of(StatusDot.State.ERROR, state.projectGateError);
        } else if (state.projectPath.isEmpty()) {
            line = StatusDot.of(StatusDot.State.IDLE, "Enter a project directory path");
        } else {
            var p = Path.of(state.projectPath);
            if (!Files.exists(p)) {
                line = StatusDot.of(StatusDot.State.ERROR, "Path does not exist");
            } else if (!Files.isDirectory(p)) {
                line = StatusDot.of(StatusDot.State.ERROR, "Path is not a directory");
            } else if (state.launchpadAware) {
                line = Line.from(
                    Span.styled(StatusDot.State.OK.glyph, Style.create().fg(Theme.go).bold()),
                    Span.styled("  Valid directory  ", Styles.body()),
                    Span.styled(Icons.SEP + "  ", Styles.dim()),
                    Span.styled(Icons.SPARK + " launchpad-aware detected",
                        Style.create().fg(Theme.ignition))
                );
            } else {
                line = StatusDot.of(StatusDot.State.OK, "Valid directory");
            }
        }
        var paragraph = Paragraph.builder().text(Text.from(line)).build();
        frame.renderWidget(paragraph, validationArea);
    }

    private void renderMatches(Frame frame, Rect area, AppState state) {
        var cardArea = centeredColumn(area, 80);
        var n = state.pathMatches.size();
        var card = Card.of("matches  " + Icons.SEP + "  " + n).build();
        var inner = card.inner(cardArea);
        frame.renderWidget(card, cardArea);

        var items = state.pathMatches.stream()
            .map(name -> ListItem.from(Line.from(Span.styled(name, Styles.body()))))
            .toArray(ListItem[]::new);

        var listState = new ListState();
        int cursor = clampCursor(state);
        listState.select(cursor);

        var list = ListWidget.builder()
            .items(items)
            .highlightStyle(Styles.listHighlight())
            .highlightSymbol(Icons.CURSOR + " ")
            .build();
        frame.renderStatefulWidget(list, inner, listState);
    }

    private static int clampCursor(AppState state) {
        if (state.pathMatches.isEmpty()) return 0;
        return Math.max(0, Math.min(state.pathMatchesCursor, state.pathMatches.size() - 1));
    }

    private static Rect centeredColumn(Rect area, int width) {
        int w = Math.min(area.width() - 4, width);
        int left = Math.max(2, (area.width() - w) / 2);
        return new Rect(area.x() + left, area.y(), w, area.height());
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        if (!state.pathMatches.isEmpty()) {
            return List.of(
                new KeyHint("↑↓", "browse"),
                new KeyHint("tab", "complete"),
                new KeyHint("enter", "continue"),
                new KeyHint("esc", "back")
            );
        }
        return List.of(
            new KeyHint("tab", "autocomplete"),
            new KeyHint("enter", "continue"),
            new KeyHint("esc", "back")
        );
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.ENTER)) {
            if (isValidProjectPath(state.projectPath)) {
                var support = projectSupportDetector.detect(Path.of(state.projectPath));
                if (!support.isSupported()) {
                    state.projectGateError = support.reason();
                    return true;
                }
                state.projectGateError = null;
                state.launchpadAware = Files.isDirectory(
                    Path.of(state.projectPath, ".launchpad", "standards"));
                state.currentScreen = state.taskFlow
                    ? AppState.Screen.SCANNING
                    : AppState.Screen.TARGET_SELECT;
                return true;
            }
            return false;
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        if (key.isKey(KeyCode.UP)) {
            if (!state.pathMatches.isEmpty()) {
                int n = state.pathMatches.size();
                state.pathMatchesCursor = (clampCursor(state) - 1 + n) % n;
            }
            return true;
        }
        if (key.isKey(KeyCode.DOWN)) {
            if (!state.pathMatches.isEmpty()) {
                int n = state.pathMatches.size();
                state.pathMatchesCursor = (clampCursor(state) + 1) % n;
            }
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            if (!state.projectPath.isEmpty()) {
                state.projectPath = state.projectPath.substring(0, state.projectPath.length() - 1);
                refreshDerived(state);
            }
            return true;
        }
        // Tab + Right autocomplete the currently-highlighted match (or the ghost
        // suggestion if no list is showing). This makes the keyboard-only flow
        // identical to picking the first list entry.
        if (key.isKey(KeyCode.TAB) || key.isKey(KeyCode.RIGHT)) {
            if (!state.pathMatches.isEmpty()) {
                var pick = state.pathMatches.get(clampCursor(state));
                state.projectPath = withTrailingSegment(state.projectPath, pick) + "/";
                refreshDerived(state);
            } else if (!state.pathSuggestion.isEmpty()) {
                state.projectPath = state.projectPath + state.pathSuggestion + "/";
                refreshDerived(state);
            }
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            state.projectPath = state.projectPath + key.character();
            refreshDerived(state);
            return true;
        }
        return false;
    }

    /**
     * Replaces the unfinished trailing segment of {@code path} with {@code segment}.
     * If {@code path} ends in `/`, just appends the segment.
     */
    private static String withTrailingSegment(String path, String segment) {
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) return path + segment;
        return path.substring(0, slash + 1) + segment;
    }

    private static void refreshDerived(AppState state) {
        state.pathSuggestion = PathAutocomplete.suggest(state.projectPath);
        state.pathMatches = PathAutocomplete.matches(state.projectPath);
        state.pathMatchesCursor = 0;
        state.launchpadAware = AppState.detectLaunchpadAware(state.projectPath);
        state.projectGateError = null;
    }

    private boolean isValidProjectPath(String path) {
        if (path.isEmpty()) return false;
        var p = Path.of(path);
        return Files.exists(p) && Files.isDirectory(p);
    }
}
