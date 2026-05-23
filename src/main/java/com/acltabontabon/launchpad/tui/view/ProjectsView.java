package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.config.RegisteredProject;
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
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Lists projects the user has used Launchpad on (from {@link ProjectRegistry}).
 * <p>
 * Purpose is twofold:
 * <ul>
 *   <li>Surface what names MCP clients (Claude Code, Cursor, Cline, Continue, Zed, ...)
 *       can use to address each project - the value the registry brings is invisible
 *       until the user can see it.</li>
 *   <li>Let the user re-open a previously-used project without retyping its path,
 *       and prune entries that point at directories which no longer exist.</li>
 * </ul>
 */
@Component
public class ProjectsView implements View {

    private final ProjectRegistry registry;

    public ProjectsView(ProjectRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),  // top spacer
                Constraint.length(3),  // heading + subhead
                Constraint.length(1),  // gap
                Constraint.min(0),     // project list
                Constraint.length(2)   // flash / hint line
            )
            .split(area);

        renderHeading(frame, rows.get(1));
        var projects = registry.all();
        clampCursor(state, projects.size());
        renderList(frame, rows.get(3), projects, state.projectsCursorIndex);
        renderFlash(frame, rows.get(4), state.projectsFlashMessage);
    }

    private static void renderHeading(Frame frame, Rect area) {
        var content = Text.from(
            Line.from(Span.styled("  Your Launchpad projects", Styles.heading())),
            Line.from(Span.styled(
                "  Address these by name from any MCP client - or press enter to re-open one here.",
                Styles.caption()))
        );
        frame.renderWidget(Paragraph.builder().text(content).build(), area);
    }

    private static void renderList(Frame frame, Rect area, List<RegisteredProject> projects, int cursor) {
        var card = Card.of(projects.isEmpty()
            ? "no projects yet"
            : projects.size() + " project" + (projects.size() == 1 ? "" : "s")).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        if (projects.isEmpty()) {
            var line = Line.from(Span.styled(
                "  Use /init or /new-task on a project. It will be listed here automatically once scanned.",
                Styles.caption()));
            frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), inner);
            return;
        }

        var lines = new ArrayList<Line>(projects.size() * 2);
        int i = 0;
        for (var project : projects) {
            var nameStyle = i == cursor
                ? Style.create().fg(Theme.fuel).bold()
                : Styles.code();
            var marker = i == cursor ? " >  " : "    ";
            lines.add(Line.from(
                Span.styled(marker, nameStyle),
                Span.styled(project.name(), nameStyle),
                Span.styled("  " + Icons.SEP + "  ", Styles.muted()),
                Span.styled(stackLabel(project.stack()), Styles.caption()),
                Span.styled("  " + Icons.SEP + "  ", Styles.muted()),
                Span.styled(relativeTime(project.lastScannedAt()), Styles.caption())
            ));
            lines.add(Line.from(Span.styled("      " + project.path(), Styles.muted())));
            i++;
        }
        var p = Paragraph.builder().text(Text.from(lines.toArray(new Line[0]))).build();
        frame.renderWidget(p, inner);
    }

    private static void renderFlash(Frame frame, Rect area, String message) {
        if (message == null || message.isBlank()) return;
        var line = Line.from(Span.styled("  " + message, Styles.caption()));
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), area);
    }

    private static String stackLabel(String stack) {
        return (stack == null || stack.isBlank()) ? "(stack unknown)" : stack;
    }

    private static String relativeTime(Instant when) {
        if (when == null) return "never scanned";
        var elapsed = Duration.between(when, Instant.now());
        if (elapsed.toMinutes() < 1) return "just now";
        if (elapsed.toHours() < 1) return elapsed.toMinutes() + "m ago";
        if (elapsed.toDays() < 1) return elapsed.toHours() + "h ago";
        if (elapsed.toDays() < 30) return elapsed.toDays() + "d ago";
        return (elapsed.toDays() / 30) + "mo ago";
    }

    private static void clampCursor(AppState state, int size) {
        if (size == 0) {
            state.projectsCursorIndex = 0;
            return;
        }
        if (state.projectsCursorIndex < 0) state.projectsCursorIndex = 0;
        if (state.projectsCursorIndex >= size) state.projectsCursorIndex = size - 1;
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        var size = registry.all().size();
        var hints = new ArrayList<KeyHint>();
        if (size > 0) {
            hints.add(new KeyHint("↑↓", "navigate"));
            hints.add(new KeyHint("enter", "open"));
            hints.add(new KeyHint("d", "remove"));
            hints.add(new KeyHint("p", "prune missing"));
        }
        hints.add(new KeyHint("esc", "back"));
        return hints;
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (event instanceof TickEvent) return true;
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.ESCAPE)) {
            state.projectsFlashMessage = "";
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }

        var projects = registry.all();
        if (projects.isEmpty()) {
            return key.isKey(KeyCode.ESCAPE);
        }

        if (key.isKey(KeyCode.UP)) {
            state.projectsCursorIndex =
                (state.projectsCursorIndex - 1 + projects.size()) % projects.size();
            return true;
        }
        if (key.isKey(KeyCode.DOWN)) {
            state.projectsCursorIndex = (state.projectsCursorIndex + 1) % projects.size();
            return true;
        }

        if (key.isKey(KeyCode.ENTER)) {
            var selected = projects.get(state.projectsCursorIndex);
            state.projectPath = selected.path();
            state.projectsFlashMessage = "";
            state.welcomeFlashMessage = "Loaded " + selected.name()
                + ". Open the command palette with / to pick an action.";
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }

        if (key.isChar('d')) {
            var selected = projects.get(state.projectsCursorIndex);
            if (registry.remove(selected.name())) {
                state.projectsFlashMessage = "Removed " + selected.name() + " from the registry.";
            }
            return true;
        }

        if (key.isChar('p')) {
            var pruned = registry.pruneMissing();
            state.projectsFlashMessage = pruned.isEmpty()
                ? "All registered paths still exist - nothing to prune."
                : "Pruned " + pruned.size() + " missing project" + (pruned.size() == 1 ? "" : "s")
                    + ": " + String.join(", ", pruned);
            return true;
        }

        return false;
    }
}
