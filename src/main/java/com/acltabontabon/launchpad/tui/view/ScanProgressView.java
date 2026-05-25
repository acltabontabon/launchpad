package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.components.Spinner;
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
import dev.tamboui.widgets.gauge.Gauge;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class ScanProgressView implements View {

    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int PROGRESS_CARD_HEIGHT = 7;
    private static final int MIDDLE_ROW_HEIGHT = 9;
    private static final int NARROW_TERMINAL_THRESHOLD = 100;

    private long tick = 0;

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        tick++;

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(1),                       // top spacer
                Constraint.length(PROGRESS_CARD_HEIGHT),    // progress card
                Constraint.length(1),                       // gap
                Constraint.length(MIDDLE_ROW_HEIGHT),       // phases + stats row
                Constraint.length(1),                       // gap
                Constraint.min(0)                           // activity log
            )
            .split(area);

        renderProgressCard(frame, rows.get(1), state);
        renderMiddleRow(frame, rows.get(3), state);
        renderActivityCard(frame, rows.get(5), state);

        if (state.quitConfirmPending) {
            renderQuitConfirm(frame, area);
        }
    }

    private void renderQuitConfirm(Frame frame, Rect area) {
        var msg = "  Press q again to quit  |  ESC to cancel  ";
        int bannerWidth = Math.min(msg.length() + 2, area.width());
        int bannerX = area.x() + (area.width() - bannerWidth) / 2;
        int bannerY = area.y() + area.height() / 2;
        var bannerArea = new Rect(bannerX, bannerY, bannerWidth, 1);
        var banner = Paragraph.builder()
            .text(Text.from(Line.from(Span.styled(msg, Styles.error()))))
            .build();
        frame.renderWidget(banner, bannerArea);
    }

    private void renderProgressCard(Frame frame, Rect area, AppState state) {
        var card = Card.of("progress").active(!state.scanComplete).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var statusGlyph = state.scanComplete
            ? (state.scanError ? Icons.CROSS : Icons.CHECK)
            : Spinner.frame(tick / 3);
        var statusStyle = state.scanError
            ? Styles.error()
            : state.scanComplete ? Styles.success() : Styles.focus();

        var lines = new ArrayList<Line>();
        lines.add(blank());
        lines.add(Line.from(
            Span.styled(" " + statusGlyph + "  ", statusStyle),
            Span.styled(state.scanMessage.get(),
                state.scanError ? Styles.error() : Styles.body())
        ));

        var item = state.currentItem.get();
        if (item != null && !item.isEmpty() && !state.scanComplete) {
            lines.add(Line.from(
                Span.styled("    " + Icons.ARROW_RIGHT + "  ", Styles.dim()),
                Span.styled(item, Styles.caption())
            ));
        } else {
            lines.add(blank());
        }
        lines.add(blank());

        var paragraph = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(paragraph, inner);

        // Gauge sits on the last row of the inner area.
        int gaugeY = inner.y() + inner.height() - 1;
        if (gaugeY >= inner.y()) {
            int progress = state.scanProgress.get();
            var gauge = Gauge.builder()
                .gaugeStyle(Style.create().fg(Theme.brand).bg(Theme.surface))
                .ratio(Math.max(0, Math.min(100, progress)) / 100.0)
                .label(progress + "%")
                .build();
            frame.renderWidget(gauge, new Rect(inner.x(), gaugeY, inner.width(), 1));
        }
    }

    private void renderMiddleRow(Frame frame, Rect area, AppState state) {
        if (area.width() < NARROW_TERMINAL_THRESHOLD) {
            // Stack vertically on narrow terminals.
            var rows = Layout.vertical()
                .constraints(
                    Constraint.percentage(50),
                    Constraint.percentage(50)
                )
                .split(area);
            renderPhasesCard(frame, rows.get(0), state);
            renderStatsCard(frame, rows.get(1), state);
            return;
        }
        var cols = Layout.horizontal()
            .constraints(
                Constraint.percentage(50),
                Constraint.length(1),
                Constraint.min(0)
            )
            .split(area);
        renderPhasesCard(frame, cols.get(0), state);
        renderStatsCard(frame, cols.get(2), state);
    }

    private void renderPhasesCard(Frame frame, Rect area, AppState state) {
        var card = Card.of("phases").build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var current = state.currentPhase.get();
        var lines = new ArrayList<Line>();
        lines.add(blank());
        lines.add(phaseRow(AppState.Phase.SCAN_FILES, "Scan project files", current, state));
        lines.add(phaseRow(AppState.Phase.AUDIT_STANDARDS, "Audit against standards", current, state));
        if (!state.taskFlow) {
            lines.add(phaseRow(AppState.Phase.ASSEMBLE, "Assemble output files", current, state));
        }
        lines.add(phaseRow(AppState.Phase.DONE, state.taskFlow ? "Ready to describe task" : "Done", current, state));

        var paragraph = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(paragraph, inner);
    }

    private void renderStatsCard(Frame frame, Rect area, AppState state) {
        var card = Card.of("stats").build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        int rulesEvaluated = state.auditRulesEvaluated.get();
        int rulesTotal = state.statsRulesTotal.get();
        int findings = state.auditFindingsCount.get();
        int must = state.auditMustCount.get();
        int should = state.auditShouldCount.get();

        var lines = new ArrayList<Line>();
        lines.add(blank());
        lines.add(statRow("Files scanned",  String.valueOf(state.statsFilesScanned.get())));
        lines.add(statRow("Packages",       String.valueOf(state.statsPackages.get())));
        lines.add(statRow("Dependencies",   String.valueOf(state.statsDependencies.get())));
        lines.add(statRow("Rules evaluated",
            rulesTotal > 0 ? rulesEvaluated + " / " + rulesTotal : String.valueOf(rulesEvaluated)));
        lines.add(statRow("Findings",
            findings == 0
                ? "clean"
                : must + " must  " + Icons.SEP + "  " + should + " should"));

        var paragraph = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(paragraph, inner);
    }

    private static Line statRow(String label, String value) {
        return Line.from(
            Span.styled("  " + label, Styles.caption()),
            Span.styled("    ", Style.create()),
            Span.styled(value, Styles.body())
        );
    }

    private void renderActivityCard(Frame frame, Rect area, AppState state) {
        var events = state.activitySnapshot();
        // Clamp scroll offset to what's actually available so the viewport
        // can never sit past the oldest event (e.g. when entries get evicted
        // from the ring buffer faster than the user scrolls).
        int maxScroll = Math.max(0, events.size() - 1);
        if (state.activityScrollOffset > maxScroll) state.activityScrollOffset = maxScroll;
        boolean pinned = state.activityScrollOffset == 0;

        var bottomTitle = pinned ? null
            : Icons.ARROW_RIGHT + " scrolled  " + state.activityScrollOffset
                + " back  ·  end / G to pin";
        var cardBuilder = Card.of("activity");
        if (bottomTitle != null) cardBuilder.bottomTitle(bottomTitle);
        var card = cardBuilder.build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        if (inner.height() <= 0) return;

        if (events.isEmpty()) {
            var hint = Paragraph.builder()
                .text(Text.from(Line.from(Span.styled("  waiting for events...", Styles.dim()))))
                .build();
            frame.renderWidget(hint, inner);
            return;
        }

        // Anchor the bottom of the slice at (size - offset), then take the
        // last `inner.height()` entries before that anchor.
        int end = events.size() - state.activityScrollOffset;
        int start = Math.max(0, end - inner.height());
        var slice = events.subList(start, end);
        var lines = new ArrayList<Line>();
        for (int i = 0; i < slice.size(); i++) {
            var evt = slice.get(i);
            boolean fadeOld = i < 2 && slice.size() == inner.height() && start > 0;
            lines.add(activityRow(evt, fadeOld));
        }

        var paragraph = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(paragraph, inner);
    }

    private static Line activityRow(AppState.ActivityEvent evt, boolean fade) {
        var ts = LocalTime.ofInstant(Instant.ofEpochMilli(evt.timestampMs()), ZoneId.systemDefault())
            .format(LOG_TS);

        var tsStyle = fade ? Styles.dim() : Styles.caption();
        var phaseStyle = fade ? Styles.dim() : phaseStyle(evt.phase());
        var messageStyle = fade ? Styles.dim() : severityStyle(evt.severity());

        // Pad the phase label to a consistent column width so messages line up.
        var phaseLabel = padRight(evt.phase(), 8);
        return Line.from(
            Span.styled("  " + ts + "  ", tsStyle),
            Span.styled(phaseLabel + "  ", phaseStyle),
            Span.styled(evt.message(), messageStyle)
        );
    }

    private static String padRight(String s, int width) {
        if (s == null) return " ".repeat(width);
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    private static Style phaseStyle(String phase) {
        return switch (phase == null ? "" : phase) {
            case "scan"   -> Style.create().fg(Theme.brand);
            case "audit"  -> Style.create().fg(Theme.fuel);
            case "assemble" -> Style.create().fg(Theme.ignition);
            case "done"   -> Styles.success();
            default       -> Styles.caption();
        };
    }

    private static Style severityStyle(String severity) {
        return switch (severity == null ? "info" : severity) {
            case "warn"    -> Styles.warning();
            case "error"   -> Styles.error();
            case "finding" -> Styles.warning();
            case "success" -> Styles.success();
            default        -> Styles.body();
        };
    }

    private Line phaseRow(AppState.Phase row, String label, AppState.Phase current, AppState state) {
        String icon;
        Style style;
        String suffix = "";

        int rowIdx = row.ordinal();
        int curIdx = current.ordinal();
        boolean done = state.scanComplete && !state.scanError;

        if (state.scanError && row == current) {
            icon = Icons.CROSS;
            style = Styles.error();
        } else if (done || rowIdx < curIdx) {
            icon = Icons.CHECK;
            style = Styles.success();
            suffix = completedSuffix(row, state);
        } else if (rowIdx == curIdx) {
            icon = Icons.CURSOR;
            style = Styles.focus();
            suffix = activeSuffix(row, state);
        } else {
            icon = Icons.DOT_EMPTY;
            style = Styles.dim();
        }
        return Line.from(Span.styled("  " + icon + "  " + label + suffix, style));
    }

    private String activeSuffix(AppState.Phase phase, AppState state) {
        long startedAt = state.phaseStartedAtMs.get();
        if (startedAt == 0) return "";
        long elapsedSec = (System.currentTimeMillis() - startedAt) / 1000;
        if (phase == AppState.Phase.AUDIT_STANDARDS) {
            int rulesEvaluated = state.auditRulesEvaluated.get();
            int findings = state.auditFindingsCount.get();
            if (rulesEvaluated > 0) {
                return "    " + elapsedSec + "s  " + Icons.SEP + "  "
                    + rulesEvaluated + " rules, " + findings + " findings";
            }
        }
        return elapsedSec > 0 ? "    " + elapsedSec + "s" : "";
    }

    private String completedSuffix(AppState.Phase phase, AppState state) {
        if (phase == AppState.Phase.AUDIT_STANDARDS && state.auditRulesEvaluated.get() > 0) {
            int findings = state.auditFindingsCount.get();
            if (findings == 0) {
                return "    " + Icons.SEP + "  clean";
            }
            return "    " + Icons.SEP + "  "
                + state.auditMustCount.get() + " must, "
                + state.auditShouldCount.get() + " should";
        }
        return "";
    }

    private static Line blank() {
        return Line.from(Span.styled("", Style.create()));
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        var hints = new ArrayList<KeyHint>();
        if (state.scanComplete && !state.scanError) {
            hints.add(new KeyHint("enter", "review generated files"));
            hints.add(new KeyHint("esc", "back"));
        } else if (!state.scanComplete) {
            hints.add(new KeyHint("esc", "cancel"));
            hints.add(new KeyHint("q", "quit"));
        } else {
            hints.add(new KeyHint("esc", "back"));
        }
        hints.add(new KeyHint("↑↓", "scroll log"));
        return hints;
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        // Activity log scrolling. Up/k = back in history, down/j = toward latest.
        // Page keys jump by ~10 entries; end / G snaps back to the latest.
        int maxScroll = Math.max(0, state.activitySnapshot().size() - 1);
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            state.activityScrollOffset = Math.min(maxScroll, state.activityScrollOffset + 1);
            return true;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            state.activityScrollOffset = Math.max(0, state.activityScrollOffset - 1);
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            state.activityScrollOffset = Math.min(maxScroll, state.activityScrollOffset + 10);
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            state.activityScrollOffset = Math.max(0, state.activityScrollOffset - 10);
            return true;
        }
        if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            state.activityScrollOffset = maxScroll;
            return true;
        }
        if (key.isKey(KeyCode.END) || key.isChar('G')) {
            state.activityScrollOffset = 0;
            return true;
        }

        if (key.isKey(KeyCode.ESCAPE)) {
            if (state.quitConfirmPending) {
                state.quitConfirmPending = false;
                return true;
            }
            if (state.scanComplete) {
                state.resetScanFlow();
                state.currentScreen = AppState.Screen.WELCOME;
                return true;
            }
            state.cancelRequested = true;
            var f = state.currentScanFuture;
            if (f != null) f.cancel(true);
            state.scanMessage.set("Cancelling...");
            return true;
        }

        if (state.scanComplete && !state.scanError && key.isKey(KeyCode.ENTER)) {
            state.currentScreen = AppState.Screen.REVIEW;
            return true;
        }
        return false;
    }
}
