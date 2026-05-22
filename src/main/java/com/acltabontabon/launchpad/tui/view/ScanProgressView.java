package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import dev.tamboui.layout.Alignment;
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
import dev.tamboui.widgets.gauge.Gauge;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

@Component
public class ScanProgressView implements View {

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private int spinnerFrame = 0;

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        spinnerFrame = (spinnerFrame + 1) % SPINNER.length;

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(3),   // title
                Constraint.length(1),   // spacer
                Constraint.length(3),   // progress gauge
                Constraint.length(3),   // status (line 1: phase + elapsed, line 2: streaming tail)
                Constraint.min(0),      // phases list
                Constraint.length(1)    // hint
            )
            .split(area);

        var title = Paragraph.builder()
            .text(Text.styled(" Step 3 of 3 - Scanning & Generating", Style.create().fg(Color.CYAN).bold()))
            .block(Block.builder()
                .borders(Borders.BOTTOM_ONLY)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(title, rows.get(0));

        var progress = state.scanProgress.get();
        var gauge = Gauge.builder()
            .block(Block.builder()
                .title(Title.from(Span.styled(" Progress ", Style.create().fg(Color.YELLOW))))
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(Color.YELLOW))
                .build())
            .gaugeStyle(Style.create().fg(Color.CYAN).bg(Color.DARK_GRAY))
            .ratio(progress / 100.0)
            .label(progress + "%")
            .build();
        frame.renderWidget(gauge, rows.get(2));

        frame.renderWidget(buildStatus(state), rows.get(3));

        var phases = Paragraph.builder()
            .text(buildPhaseList(state))
            .block(Block.builder()
                .title(Title.from(Span.styled(" Phases ", Style.create().fg(Color.DARK_GRAY))))
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(phases, rows.get(4));

        if (state.scanComplete && !state.scanError) {
            var hint = Paragraph.builder()
                .text(Text.styled(
                    " Press Enter to review generated files",
                    Style.create().fg(Color.YELLOW).bold()
                ))
                .alignment(Alignment.CENTER)
                .build();
            frame.renderWidget(hint, rows.get(5));
        }
    }

    private Paragraph buildStatus(AppState state) {
        var spinner = state.scanComplete ? (state.scanError ? "✗" : "✓") : SPINNER[spinnerFrame];
        var messageStyle = state.scanError
            ? Style.create().fg(Color.RED)
            : state.scanComplete
                ? Style.create().fg(Color.GREEN)
                : Style.create().fg(Color.WHITE);

        var lines = new ArrayList<Line>();
        lines.add(Line.from(Span.styled(" " + spinner + "  " + state.scanMessage.get(), messageStyle)));

        if (isAiPhase(state.currentPhase.get()) && !state.scanComplete) {
            var tail = state.streamTail.get();
            if (tail != null && !tail.isEmpty()) {
                lines.add(Line.from(Span.styled(" > " + tail, Style.create().fg(Color.DARK_GRAY).italic())));
            }
        }

        return Paragraph.builder().text(Text.from(lines)).build();
    }

    private Text buildPhaseList(AppState state) {
        var current = state.currentPhase.get();
        var target = state.selectedTarget.displayName;
        var lines = new ArrayList<Line>();

        lines.add(phaseRow(AppState.Phase.SCAN_FILES, "Scan project files", current, state));
        lines.add(phaseRow(AppState.Phase.GENERATE_SUMMARY, "Generate project summary", current, state));
        lines.add(phaseRow(AppState.Phase.GENERATE_TARGET, "Generate " + target + " content", current, state));
        lines.add(phaseRow(AppState.Phase.ASSEMBLE, "Assemble output files", current, state));
        lines.add(phaseRow(AppState.Phase.DONE, "Done", current, state));

        return Text.from(lines);
    }

    private Line phaseRow(AppState.Phase row, String label, AppState.Phase current, AppState state) {
        String icon;
        Style style;
        String suffix = "";

        int rowIdx = row.ordinal();
        int curIdx = current.ordinal();
        boolean done = state.scanComplete && !state.scanError;

        if (state.scanError && row == current) {
            icon = "✗";
            style = Style.create().fg(Color.RED);
        } else if (done || rowIdx < curIdx) {
            icon = "✓";
            style = Style.create().fg(Color.GREEN);
        } else if (rowIdx == curIdx) {
            icon = "→";
            style = Style.create().fg(Color.YELLOW).bold();
            suffix = activeSuffix(row, state);
        } else {
            icon = "○";
            style = Style.create().fg(Color.DARK_GRAY);
        }

        return Line.from(Span.styled(" " + icon + "  " + label + suffix, style));
    }

    private String activeSuffix(AppState.Phase phase, AppState state) {
        long startedAt = state.phaseStartedAtMs.get();
        if (startedAt == 0) return "";
        long elapsedSec = (System.currentTimeMillis() - startedAt) / 1000;

        if (isAiPhase(phase)) {
            int chunks = state.streamedChunks.get();
            return "  (" + elapsedSec + "s, " + chunks + " chunks)";
        }
        return elapsedSec > 0 ? "  (" + elapsedSec + "s)" : "";
    }

    private boolean isAiPhase(AppState.Phase phase) {
        return phase == AppState.Phase.GENERATE_SUMMARY || phase == AppState.Phase.GENERATE_TARGET;
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
