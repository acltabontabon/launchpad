package com.acltabontabon.launchpad.tui.components;

import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.theme.Icons;
import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent single-row header rendered above every view. Composes the
 * {@link Brand} mark on the left with a derived breadcrumb in the centre and
 * version / metadata on the right.
 */
public final class Header {

    private static final String USER_HOME = System.getProperty("user.home");
    private static final String VERSION = readVersion();

    private Header() {}

    public static void render(Frame frame, Rect area, AppState state) {
        var spans = new ArrayList<Span>();
        spans.addAll(Brand.mark());

        // breadcrumb() returns the complete breadcrumb (its own internal arrows
        // and separators included). We add exactly one separator between the
        // brand mark and the breadcrumb itself - no per-span loop, which used
        // to stack a `·` in front of every internal connector.
        var crumbs = breadcrumb(state);
        if (!crumbs.isEmpty()) {
            spans.add(Span.styled("  " + Icons.SEP + "  ", Styles.dim()));
            spans.addAll(crumbs);
        }

        if (state.launchpadAware && state.currentScreen != AppState.Screen.WELCOME) {
            spans.add(Span.styled("  ", Style.create()));
            spans.add(Span.styled(Icons.SPARK + " launchpad-aware",
                Style.create().fg(Theme.ignition)));
        }

        // Right-aligned version: pad spaces so it lands at the right edge.
        int currentWidth = visibleWidth(spans);
        int versionWidth = VERSION.length() + 1; // leading space
        int pad = Math.max(2, area.width() - currentWidth - versionWidth - 1);
        spans.add(Span.styled(" ".repeat(pad), Style.create()));
        spans.add(Span.styled(VERSION, Styles.dim()));

        var widget = Paragraph.builder()
            .text(Text.from(Line.from(spans.toArray(new Span[0]))))
            .build();
        frame.renderWidget(widget, area);
    }

    private static List<Span> breadcrumb(AppState state) {
        var segs = new ArrayList<Span>();
        switch (state.currentScreen) {
            case WELCOME -> {
                var model = state.activeModel == null ? "" : state.activeModel;
                if (model.isBlank()) {
                    segs.add(Span.styled("model: not configured", Styles.dim()));
                } else {
                    segs.add(Span.styled("model", Styles.dim()));
                    segs.add(Span.styled("  " + model, Styles.muted()));
                }
            }
            case PROJECT_SELECT -> {
                segs.add(Span.styled("Init", Styles.muted()));
                segs.add(Span.styled("  " + Icons.ARROW_RIGHT + "  ", Styles.dim()));
                segs.add(Span.styled("Project", Style.create().fg(Theme.text)));
            }
            case TARGET_SELECT -> {
                segs.add(Span.styled(shortenPath(state.projectPath), Styles.muted()));
                segs.add(Span.styled("  " + Icons.ARROW_RIGHT + "  ", Styles.dim()));
                segs.add(Span.styled("Target", Style.create().fg(Theme.text)));
            }
            case SCANNING -> {
                segs.add(Span.styled("Generating " + state.selectedTarget.displayName,
                    Style.create().fg(Theme.text)));
                if (!state.projectPath.isEmpty()) {
                    segs.add(Span.styled("  " + Icons.SEP + "  ", Styles.dim()));
                    segs.add(Span.styled(shortenPath(state.projectPath), Styles.muted()));
                }
            }
            case REVIEW -> {
                int n = state.generatedFiles == null ? 0 : state.generatedFiles.size();
                segs.add(Span.styled("Review", Style.create().fg(Theme.text)));
                segs.add(Span.styled("  " + Icons.SEP + "  ", Styles.dim()));
                segs.add(Span.styled(n + " files generated", Styles.muted()));
            }
            case SETTINGS -> segs.add(Span.styled("Settings", Style.create().fg(Theme.text)));
            case TASK_INPUT -> {
                segs.add(Span.styled("New Task", Styles.muted()));
                segs.add(Span.styled("  " + Icons.ARROW_RIGHT + "  ", Styles.dim()));
                segs.add(Span.styled("Describe", Style.create().fg(Theme.text)));
            }
            case TASK_INTERVIEW -> {
                segs.add(Span.styled("New Task", Styles.muted()));
                segs.add(Span.styled("  " + Icons.ARROW_RIGHT + "  ", Styles.dim()));
                segs.add(Span.styled("Interview", Style.create().fg(Theme.text)));
            }
            case TASK_RESULT -> {
                segs.add(Span.styled("New Task", Styles.muted()));
                segs.add(Span.styled("  " + Icons.ARROW_RIGHT + "  ", Styles.dim()));
                segs.add(Span.styled("Prompt", Style.create().fg(Theme.text)));
            }
        }
        if (state.currentScreen != AppState.Screen.WELCOME
            && state.currentScreen != AppState.Screen.PROJECT_SELECT
            && state.currentScreen != AppState.Screen.SETTINGS
            && state.currentScreen != AppState.Screen.TARGET_SELECT
            && state.currentScreen != AppState.Screen.SCANNING
            && state.currentScreen != AppState.Screen.REVIEW) {
            segs.add(Span.styled("  " + Icons.SEP + "  ", Styles.dim()));
            segs.add(Span.styled(Icons.ARROW_TARGET + " " + state.selectedTarget.displayName,
                Style.create().fg(Theme.brand)));
        }
        return segs;
    }

    private static int visibleWidth(List<Span> spans) {
        int w = 0;
        for (var s : spans) w += codePointCount(s.content());
        return w;
    }

    private static int codePointCount(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    private static String shortenPath(String path) {
        if (path == null || path.isEmpty()) return "";
        var display = path.startsWith(USER_HOME) ? "~" + path.substring(USER_HOME.length()) : path;
        if (display.length() <= 36) return display;
        var slash = display.lastIndexOf('/');
        var tail = slash >= 0 ? display.substring(slash) : display;
        return "…" + tail;
    }

    private static String readVersion() {
        var pkg = Header.class.getPackage();
        var v = pkg == null ? null : pkg.getImplementationVersion();
        return "v" + (v == null ? "0.2.0" : v);
    }
}
