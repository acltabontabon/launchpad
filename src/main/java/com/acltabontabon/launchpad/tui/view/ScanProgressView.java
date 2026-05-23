package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.components.Spinner;
import com.acltabontabon.launchpad.tui.components.Stepper;
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

import java.util.ArrayList;
import java.util.List;

@Component
public class ScanProgressView implements View {

    private long tick = 0;

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        tick++;

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(1),                 // top spacer
                Constraint.length(Stepper.rowsNeeded()), // stepper
                Constraint.length(1),                 // gap
                Constraint.length(7),                 // progress card (spinner + gauge + tail)
                Constraint.length(1),                 // gap
                Constraint.min(0)                     // phases card
            )
            .split(area);

        Stepper.render(frame, rows.get(1), state, tick / 3);
        renderProgressCard(frame, rows.get(3), state);
        renderPhasesCard(frame, rows.get(5), state);
    }

    private void renderProgressCard(Frame frame, Rect area, AppState state) {
        var card = Card.of("progress").active(!state.scanComplete).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var lines = new ArrayList<Line>();
        lines.add(blank());

        // Status line: spinner / check / cross + message
        var statusGlyph = state.scanComplete
            ? (state.scanError ? Icons.CROSS : Icons.CHECK)
            : Spinner.frame(tick / 3);
        var statusStyle = state.scanError
            ? Styles.error()
            : state.scanComplete ? Styles.success() : Styles.focus();
        lines.add(Line.from(
            Span.styled(" " + statusGlyph + "  ", statusStyle),
            Span.styled(state.scanMessage.get(),
                state.scanError ? Styles.error() : Styles.body())
        ));
        lines.add(blank());

        // Inline gauge - rendered as a separate widget below
        var gaugeArea = new Rect(inner.x(), inner.y() + lines.size(), inner.width(),
            Math.max(1, inner.height() - lines.size() - 2));
        if (gaugeArea.height() >= 1) {
            int progress = state.scanProgress.get();
            var gauge = Gauge.builder()
                .gaugeStyle(Style.create().fg(Theme.brand).bg(Theme.surface))
                .ratio(Math.max(0, Math.min(100, progress)) / 100.0)
                .label(progress + "%")
                .build();
            frame.renderWidget(gauge, new Rect(gaugeArea.x(), gaugeArea.y(), gaugeArea.width(), 1));
        }

        // Render the header lines above the gauge.
        var paragraph = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(paragraph, inner);

        // Tail text below the gauge (italic, muted) if available.
        if (isAiPhase(state.currentPhase.get()) && !state.scanComplete) {
            var tail = state.streamTail.get();
            if (tail != null && !tail.isEmpty()) {
                var tailArea = new Rect(inner.x(), gaugeArea.y() + 2, inner.width(),
                    Math.max(0, inner.height() - lines.size() - 3));
                if (tailArea.height() >= 1) {
                    var tailLine = Paragraph.builder()
                        .text(Text.from(Line.from(
                            Span.styled(" " + Icons.ARROW_RIGHT + "  ", Styles.dim()),
                            Span.styled(tail, Styles.caption())
                        )))
                        .build();
                    frame.renderWidget(tailLine, tailArea);
                }
            }
        }
    }

    private void renderPhasesCard(Frame frame, Rect area, AppState state) {
        var card = Card.of("phases").build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var current = state.currentPhase.get();
        var target = state.selectedTarget.displayName;
        var lines = new ArrayList<Line>();
        lines.add(blank());
        lines.add(phaseRow(AppState.Phase.SCAN_FILES, "Scan project files", current, state));
        lines.add(phaseRow(AppState.Phase.GENERATE_SUMMARY, "Generate project summary", current, state));
        lines.add(phaseRow(AppState.Phase.GENERATE_TARGET, "Generate " + target + " content", current, state));
        lines.add(phaseRow(AppState.Phase.ASSEMBLE, "Assemble output files", current, state));
        lines.add(phaseRow(AppState.Phase.DONE, "Done", current, state));

        var paragraph = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(paragraph, inner);
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
        if (isAiPhase(phase)) {
            int chunks = state.streamedChunks.get();
            return "    " + elapsedSec + "s  " + Icons.SEP + "  " + chunks + " chunks";
        }
        return elapsedSec > 0 ? "    " + elapsedSec + "s" : "";
    }

    private boolean isAiPhase(AppState.Phase phase) {
        return phase == AppState.Phase.GENERATE_SUMMARY || phase == AppState.Phase.GENERATE_TARGET;
    }

    private static Line blank() {
        return Line.from(Span.styled("", Style.create()));
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        if (state.scanComplete && !state.scanError) {
            return List.of(
                new KeyHint("enter", "review generated files"),
                new KeyHint("esc", "cancel")
            );
        }
        return List.of(new KeyHint("esc", "cancel"));
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (event instanceof KeyEvent key && state.scanComplete && !state.scanError
                && key.isKey(KeyCode.ENTER)) {
            state.currentScreen = AppState.Screen.REVIEW;
            return true;
        }
        return false;
    }
}
