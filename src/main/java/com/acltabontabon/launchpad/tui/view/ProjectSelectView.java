package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.config.RegisteredProject;
import com.acltabontabon.launchpad.scanner.ProjectSupportDetector;
import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.discovery.DiscoveredProject;
import com.acltabontabon.launchpad.tui.discovery.LiveProjectSearch;
import com.acltabontabon.launchpad.tui.discovery.ProjectDiscovery;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Fuzzy-filterable picker for the project to scan. Unifies two sources:
 * <ul>
 *   <li>recent projects from {@link ProjectRegistry} (most-recent first)</li>
 *   <li>discovered Spring Boot projects (Maven or Gradle) from {@link ProjectDiscovery}</li>
 * </ul>
 * Typing filters by name and path; arrows move the cursor; Enter runs the
 * support gate and advances. There is no text-path mode - if a project is not
 * in any common dev root the user adds it via the Projects screen.
 */
@Component
public class ProjectSelectView implements View {

    private final ProjectSupportDetector projectSupportDetector;
    private final ProjectRegistry registry;
    private final ProjectDiscovery discovery;
    private final LiveProjectSearch liveSearch;
    private final LaunchpadSettings settings;
    private final ProjectionSelectView projectionSelectView;

    public ProjectSelectView(ProjectSupportDetector projectSupportDetector,
                             ProjectRegistry registry,
                             ProjectDiscovery discovery,
                             LiveProjectSearch liveSearch,
                             LaunchpadSettings settings,
                             ProjectionSelectView projectionSelectView) {
        this.projectSupportDetector = projectSupportDetector;
        this.registry = registry;
        this.discovery = discovery;
        this.liveSearch = liveSearch;
        this.settings = settings;
        this.projectionSelectView = projectionSelectView;
    }

    /** Unified row for the picker. {@code lastScannedAt} is null for discovered-only entries. */
    private record Entry(Path path, String name, String stack, Instant lastScannedAt, boolean recent) {}

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        discovery.triggerOnce();

        boolean pathMode = isPathInput(state.projectPickerQuery);
        var entries = pathMode ? List.<Entry>of() : buildEntries(state.projectPickerQuery);
        clampCursor(state, entries.size());

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),  // top spacer
                Constraint.length(3),  // heading + subhead
                Constraint.length(3),  // search input card
                Constraint.length(1),  // gap
                Constraint.min(0),     // list
                Constraint.length(1),  // status / error line
                Constraint.length(1)   // bottom spacer
            )
            .split(area);

        renderHeading(frame, rows.get(1));
        renderSearch(frame, rows.get(2), state, entries.size(), pathMode);
        if (pathMode) {
            renderPathPreview(frame, rows.get(4), state.projectPickerQuery);
        } else {
            renderList(frame, rows.get(4), entries, state);
        }
        renderStatus(frame, rows.get(5), state);
    }

    private void renderHeading(Frame frame, Rect area) {
        var headingArea = centeredColumn(area, 88);
        var heading = Paragraph.builder()
            .text(Text.from(
                Line.from(Span.styled("Choose a project", Styles.heading())),
                Line.from(Span.styled(
                    "Type to filter, or paste a path starting with / or ~ to open one directly.",
                    Styles.caption()))
            ))
            .build();
        frame.renderWidget(heading, headingArea);
    }

    private void renderSearch(Frame frame, Rect area, AppState state, int matchCount, boolean pathMode) {
        var cardArea = centeredColumn(area, 88);

        String bottomLabel;
        if (pathMode) {
            bottomLabel = "path mode " + Icons.SEP + " enter to open";
        } else if (liveSearch.isSearching()) {
            bottomLabel = spinnerFrame() + " searching your home directory " + Icons.SEP
                + " " + matchCount + " match" + (matchCount == 1 ? "" : "es") + " so far";
        } else {
            var totalKnown = registry.all().size() + discovery.snapshot().size();
            bottomLabel = discovery.isScanning()
                ? spinnerFrame() + " scanning common dev roots " + Icons.SEP + " " + matchCount + " of " + totalKnown
                : matchCount + " of " + totalKnown + " project" + (totalKnown == 1 ? "" : "s");
        }

        var card = Card.of(pathMode ? "path" : "search")
            .active(true).bottomTitle(bottomLabel).build();
        var inner = card.inner(cardArea);
        frame.renderWidget(card, cardArea);

        Line inputLine;
        if (state.projectPickerQuery.isEmpty()) {
            inputLine = Line.from(
                Span.styled("█", Style.create().fg(Theme.fuel)),
                Span.styled("  start typing a project name " + Icons.SEP
                    + "  ↑↓ to browse " + Icons.SEP + "  enter to open", Styles.dim())
            );
        } else {
            inputLine = Line.from(
                Span.styled(state.projectPickerQuery, Styles.code()),
                Span.styled("█", Style.create().fg(Theme.fuel))
            );
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(inputLine)).build(), inner);
    }

    private void renderPathPreview(Frame frame, Rect area, String query) {
        var cardArea = centeredColumn(area, 88);
        var resolved = expandHome(query);
        var card = Card.of("open this path").build();
        var inner = card.inner(cardArea);
        frame.renderWidget(card, cardArea);

        var lines = new ArrayList<Line>();
        lines.add(Line.from(
            Span.styled("  " + Icons.CURSOR + "  ", Styles.focus()),
            Span.styled(resolved.toString(), Style.create().fg(Theme.text).bold())
        ));
        lines.add(Line.from(Span.raw("")));

        if (!Files.exists(resolved)) {
            lines.add(Line.from(
                Span.styled("    " + Icons.CROSS + "  ", Style.create().fg(Theme.abort).bold()),
                Span.styled("path does not exist", Style.create().fg(Theme.abort))
            ));
        } else if (!Files.isDirectory(resolved)) {
            lines.add(Line.from(
                Span.styled("    " + Icons.CROSS + "  ", Style.create().fg(Theme.abort).bold()),
                Span.styled("not a directory", Style.create().fg(Theme.abort))
            ));
        } else {
            var gate = projectSupportDetector.detect(resolved);
            if (gate.isSupported()) {
                lines.add(Line.from(
                    Span.styled("    " + Icons.CHECK + "  ", Style.create().fg(Theme.go).bold()),
                    Span.styled(gate.framework() + " detected " + Icons.SEP + " press enter to scan",
                        Styles.body())
                ));
            } else {
                lines.add(Line.from(
                    Span.styled("    " + Icons.WARN + "  ", Style.create().fg(Theme.caution).bold()),
                    Span.styled(gate.reason(), Style.create().fg(Theme.caution))
                ));
            }
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.toArray(new Line[0]))).build(), inner);
    }

    private void renderList(Frame frame, Rect area, List<Entry> entries, AppState state) {
        var cardArea = centeredColumn(area, 88);
        var card = Card.of(entries.isEmpty() ? "no matches" : "projects").build();
        var inner = card.inner(cardArea);
        frame.renderWidget(card, cardArea);

        if (entries.isEmpty()) {
            renderEmptyState(frame, inner, state);
            return;
        }

        // Each entry takes two visible lines (header row + path). Compute how many fit,
        // then window the list around the cursor so the selection is always on-screen.
        int rowsPerEntry = 2;
        int visibleEntries = Math.max(1, inner.height() / rowsPerEntry);
        int cursor = state.projectPickerCursor;
        int start = Math.max(0, Math.min(cursor - visibleEntries / 2,
            entries.size() - visibleEntries));
        start = Math.max(0, start);
        int end = Math.min(entries.size(), start + visibleEntries);

        var lines = new ArrayList<Line>(visibleEntries * rowsPerEntry);
        for (int i = start; i < end; i++) {
            var e = entries.get(i);
            boolean selected = i == cursor;
            lines.add(headerRow(e, selected, inner.width()));
            lines.add(pathRow(e, selected, inner.width()));
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.toArray(new Line[0]))).build(), inner);
    }

    private static Line headerRow(Entry e, boolean selected, int width) {
        var marker = selected ? Icons.CURSOR + " " : "  ";
        var nameStyle = selected ? Styles.focus() : Style.create().fg(Theme.text).bold();
        var spans = new ArrayList<Span>();
        spans.add(Span.styled(marker, selected ? Styles.focus() : Styles.muted()));
        spans.add(Span.styled(e.name(), nameStyle));
        spans.add(Span.raw("  "));
        spans.add(Span.styled(stackChip(e.stack()), e.recent() ? Styles.brandChip() : Styles.muteChip()));
        if (e.recent()) {
            spans.add(Span.raw("  "));
            spans.add(Span.styled("recent " + Icons.SEP + " " + relativeTime(e.lastScannedAt()),
                Styles.caption()));
        }
        return Line.from(spans.toArray(new Span[0]));
    }

    private static Line pathRow(Entry e, boolean selected, int width) {
        var indent = "    ";
        var path = humanPath(e.path());
        return Line.from(
            Span.styled(indent + path, selected ? Styles.muted() : Styles.dim())
        );
    }

    private static void renderEmptyState(Frame frame, Rect inner, AppState state) {
        var lines = new ArrayList<Line>();
        if (state.projectPickerQuery.isEmpty()) {
            lines.add(Line.from(Span.styled(
                "  No Spring Boot projects found under your home directory yet.",
                Styles.caption())));
            lines.add(Line.from(Span.raw("")));
            lines.add(Line.from(Span.styled(
                "  Launchpad checks ~/Workspace, ~/code, ~/dev, ~/src, ~/Projects (and a few others).",
                Styles.dim())));
            lines.add(Line.from(Span.styled(
                "  Move a project there, or scan one for the first time and it will appear here.",
                Styles.dim())));
        } else {
            lines.add(Line.from(Span.styled(
                "  Nothing matches \"" + state.projectPickerQuery + "\".",
                Styles.caption())));
            lines.add(Line.from(Span.raw("")));
            lines.add(Line.from(Span.styled(
                "  Press backspace to clear the filter, or esc to go back.", Styles.dim())));
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.toArray(new Line[0]))).build(), inner);
    }

    private static void renderStatus(Frame frame, Rect area, AppState state) {
        if (state.projectGateError == null || state.projectGateError.isEmpty()) return;
        var statusArea = centeredColumn(area, 88);
        var line = Line.from(
            Span.styled("  " + Icons.WARN + "  ", Style.create().fg(Theme.abort).bold()),
            Span.styled(state.projectGateError, Style.create().fg(Theme.abort))
        );
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), statusArea);
    }

    // ── data assembly ─────────────────────────────────────────────────────────

    private List<Entry> buildEntries(String query) {
        var recents = registry.all();
        var discovered = discovery.snapshot();
        var seen = new HashSet<String>();
        var merged = new ArrayList<Entry>(recents.size() + discovered.size());

        for (var r : recents) {
            var key = dedupKey(Path.of(r.path()));
            if (seen.add(key)) {
                merged.add(new Entry(Path.of(r.path()), r.name(), r.stack(), r.lastScannedAt(), true));
            }
        }
        for (var d : discovered) {
            var key = dedupKey(d.path());
            if (seen.add(key)) {
                merged.add(new Entry(d.path(), d.name(), d.framework(), null, false));
            }
        }
        // Live-search hits get appended only when they match the current query - a
        // stale list from a prior search shouldn't bleed into a new filter.
        var liveQuery = liveSearch.resultsQuery();
        if (query != null && !query.isBlank() && query.equals(liveQuery)) {
            for (var d : liveSearch.results()) {
                var key = dedupKey(d.path());
                if (seen.add(key)) {
                    merged.add(new Entry(d.path(), d.name(), d.framework(), null, false));
                }
            }
        }

        if (query == null || query.isBlank()) return merged;
        var needle = query.toLowerCase(Locale.ROOT);
        return merged.stream()
            .filter(e -> e.name().toLowerCase(Locale.ROOT).contains(needle)
                || e.path().toString().toLowerCase(Locale.ROOT).contains(needle))
            .toList();
    }

    private static boolean isPathInput(String query) {
        return query != null && (query.startsWith("/") || query.startsWith("~"));
    }

    /** Expand a leading {@code ~} to {@code $HOME}; otherwise pass through as an absolute path. */
    private static Path expandHome(String raw) {
        var trimmed = raw.trim();
        if (trimmed.startsWith("~")) {
            var rest = trimmed.length() == 1 ? "" : trimmed.substring(1).replaceFirst("^/", "");
            return Path.of(System.getProperty("user.home"), rest);
        }
        return Path.of(trimmed);
    }

    /**
     * Best-effort canonical key for dedup across registry recents and discovery hits.
     * Falls back to a case-insensitive normalised path string when the file isn't
     * readable (registry can outlive the directory).
     */
    private static String dedupKey(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (java.io.IOException e) {
            return p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        }
    }

    private static String stackChip(String stack) {
        if (stack == null || stack.isBlank()) return " spring boot ";
        // Compact label - if the registry stored "Java/Spring Boot", show just "spring boot".
        var lower = stack.toLowerCase(Locale.ROOT);
        if (lower.contains("spring")) return " spring boot ";
        return " " + lower + " ";
    }

    private static String humanPath(Path path) {
        var home = System.getProperty("user.home");
        var s = path.toString();
        if (home != null && s.startsWith(home)) {
            return "~" + s.substring(home.length());
        }
        return s;
    }

    private static String relativeTime(Instant when) {
        if (when == null) return "never";
        var elapsed = Duration.between(when, Instant.now());
        if (elapsed.toMinutes() < 1) return "just now";
        if (elapsed.toHours() < 1) return elapsed.toMinutes() + "m ago";
        if (elapsed.toDays() < 1) return elapsed.toHours() + "h ago";
        if (elapsed.toDays() < 30) return elapsed.toDays() + "d ago";
        return (elapsed.toDays() / 30) + "mo ago";
    }

    private static void clampCursor(AppState state, int size) {
        if (size == 0) {
            state.projectPickerCursor = 0;
            return;
        }
        if (state.projectPickerCursor < 0) state.projectPickerCursor = 0;
        if (state.projectPickerCursor >= size) state.projectPickerCursor = size - 1;
    }

    private static Rect centeredColumn(Rect area, int width) {
        int w = Math.min(area.width() - 4, width);
        int left = Math.max(2, (area.width() - w) / 2);
        return new Rect(area.x() + left, area.y(), w, area.height());
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        return List.of(
            new KeyHint("↑↓", "browse"),
            new KeyHint("enter", "scan"),
            new KeyHint("type", "filter"),
            new KeyHint("esc", "back")
        );
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.ESCAPE)) {
            state.projectPickerQuery = "";
            state.projectPickerCursor = 0;
            state.projectGateError = null;
            liveSearch.cancel();
            state.nav.currentScreen = AppState.Screen.WELCOME;
            return true;
        }

        boolean pathMode = isPathInput(state.projectPickerQuery);
        var entries = pathMode ? List.<Entry>of() : buildEntries(state.projectPickerQuery);

        if (key.isKey(KeyCode.UP)) {
            if (!entries.isEmpty()) {
                int n = entries.size();
                state.projectPickerCursor = (state.projectPickerCursor - 1 + n) % n;
            }
            return true;
        }
        if (key.isKey(KeyCode.DOWN)) {
            if (!entries.isEmpty()) {
                int n = entries.size();
                state.projectPickerCursor = (state.projectPickerCursor + 1) % n;
            }
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            if (pathMode) {
                return select(state, expandHome(state.projectPickerQuery));
            }
            if (entries.isEmpty()) return true;
            clampCursor(state, entries.size());
            var chosen = entries.get(state.projectPickerCursor);
            return select(state, chosen.path());
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            if (!state.projectPickerQuery.isEmpty()) {
                state.projectPickerQuery = state.projectPickerQuery
                    .substring(0, state.projectPickerQuery.length() - 1);
                state.projectPickerCursor = 0;
                state.projectGateError = null;
                refreshLiveSearch(state.projectPickerQuery);
            }
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            state.projectPickerQuery = state.projectPickerQuery + key.character();
            state.projectPickerCursor = 0;
            state.projectGateError = null;
            refreshLiveSearch(state.projectPickerQuery);
            return true;
        }
        return false;
    }

    /** Path-mode queries don't trigger filesystem search - the user is already typing a literal target. */
    private void refreshLiveSearch(String query) {
        if (isPathInput(query)) {
            liveSearch.cancel();
        } else {
            liveSearch.submit(query);
        }
    }

    private static String spinnerFrame() {
        int frame = (int) Math.floorMod(System.currentTimeMillis() / 80L, Icons.SPINNER.length);
        return Icons.SPINNER[frame];
    }

    private boolean select(AppState state, Path path) {
        if (!Files.isDirectory(path)) {
            state.projectGateError = "Path no longer exists: " + path;
            return true;
        }
        var support = projectSupportDetector.detect(path);
        if (!support.isSupported()) {
            state.projectGateError = support.reason();
            return true;
        }
        state.projectGateError = null;
        state.projectPath = path.toAbsolutePath().normalize().toString();
        state.launchpadAware = AppState.detectLaunchpadAware(state.projectPath);
        if (!settings.snapshot().hasProjections()) {
            // First-run gate: developer hasn't picked their AI tools yet.
            // Pop the picker before scanning so the generated output set
            // matches what they actually use.
            state.projectionPickerReturnsToSettings = false;
            projectionSelectView.seedSelection(state);
            state.nav.currentScreen = AppState.Screen.PROJECTION_SELECT;
        } else {
            state.nav.currentScreen = AppState.Screen.SCANNING;
        }
        return true;
    }
}
