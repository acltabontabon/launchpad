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
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class ScanProgressView implements View {

    // Spinner frames for animation while waiting
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
                Constraint.length(2),   // status message
                Constraint.min(0),      // phases list
                Constraint.length(1)    // hint
            )
            .split(area);

        // Title
        var title = Paragraph.builder()
            .text(Text.styled(" Step 3 of 3 - Scanning & Generating", Style.create().fg(Color.CYAN).bold()))
            .block(Block.builder()
                .borders(Borders.BOTTOM_ONLY)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(title, rows.get(0));

        // Progress bar
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

        // Status message with spinner
        var spinner = state.scanComplete ? (state.scanError ? "✗" : "✓") : SPINNER[spinnerFrame];
        var messageStyle = state.scanError
            ? Style.create().fg(Color.RED)
            : state.scanComplete
                ? Style.create().fg(Color.GREEN)
                : Style.create().fg(Color.WHITE);

        var message = Paragraph.builder()
            .text(Text.styled(" " + spinner + "  " + state.scanMessage.get(), messageStyle))
            .build();
        frame.renderWidget(message, rows.get(3));

        // Phase list
        var phases = Paragraph.builder()
            .text(buildPhaseList(progress, state))
            .block(Block.builder()
                .title(Title.from(Span.styled(" Phases ", Style.create().fg(Color.DARK_GRAY))))
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(phases, rows.get(4));

        // Transition hint when done
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

    private Text buildPhaseList(int progress, AppState state) {
        var lines = new ArrayList<Line>();

        lines.add(phaseRow("Scan project files", 10, progress));
        lines.add(phaseRow("Parse dependencies", 25, progress));
        lines.add(phaseRow("Generate project summary", 50, progress));
        lines.add(phaseRow("Generate skills / rules", 75, progress));
        lines.add(phaseRow("Assemble output files", 90, progress));
        lines.add(phaseRow("Done", 100, progress));

        return Text.from(lines);
    }

    private Line phaseRow(String label, int threshold, int progress) {
        String icon;
        Style style;
        if (progress >= threshold) {
            icon = "✓";
            style = Style.create().fg(Color.GREEN);
        } else if (progress >= threshold - 15) {
            icon = "→";
            style = Style.create().fg(Color.YELLOW);
        } else {
            icon = "○";
            style = Style.create().fg(Color.DARK_GRAY);
        }
        return Line.from(Span.styled(" " + icon + "  " + label, style));
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
