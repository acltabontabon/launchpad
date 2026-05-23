package com.acltabontabon.launchpad.tui.components;

import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.theme.Icons;
import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
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
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal step indicator for the scanning pipeline.
 * <pre>{@code
 *   ●━━━━●━━━━◐━━━━○━━━━○
 *  Read  Detect Stack Assemble Done
 * }</pre>
 *
 * Driven by {@link AppState#currentPhase} and {@link AppState#scanError}.
 */
public final class Stepper {

    private Stepper() {}

    private record Step(AppState.Phase phase, String label) {}

    private static final List<Step> INIT_STEPS = List.of(
        new Step(AppState.Phase.SCAN_FILES, "Scan"),
        new Step(AppState.Phase.GENERATE_SUMMARY, "Summary"),
        new Step(AppState.Phase.GENERATE_TARGET, "Target"),
        new Step(AppState.Phase.ASSEMBLE, "Assemble"),
        new Step(AppState.Phase.DONE, "Done")
    );

    // /new-task only runs Scan + Audit on this screen, then jumps to TASK_INPUT.
    // The remaining stops (Describe / Interview / Prompt) are downstream screens;
    // showing them here gives the user a map of the rest of the flow.
    private static final List<Step> TASK_STEPS = List.of(
        new Step(AppState.Phase.SCAN_FILES, "Scan"),
        new Step(AppState.Phase.AUDIT_STANDARDS, "Audit"),
        new Step(AppState.Phase.DONE, "Describe"),
        new Step(null, "Interview"),
        new Step(null, "Prompt")
    );

    private static List<Step> stepsFor(AppState state) {
        return state.taskFlow ? TASK_STEPS : INIT_STEPS;
    }

    public static int rowsNeeded() { return 3; }

    public static void render(Frame frame, Rect area, AppState state, long tickCounter) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(1), // spacing
                Constraint.length(1), // dots + bars
                Constraint.length(1)  // labels
            )
            .split(area);

        var spinnerFrame = Spinner.frame(tickCounter);
        var steps = stepsFor(state);
        int currentIdx = currentIndex(state, steps);

        var dotsLine = buildDotsLine(area.width(), currentIdx, state.scanError, spinnerFrame, steps);
        var labelsLine = buildLabelsLine(area.width(), currentIdx, state.scanError, steps);

        frame.renderWidget(
            Paragraph.builder().text(Text.from(dotsLine)).alignment(Alignment.CENTER).build(),
            rows.get(1)
        );
        frame.renderWidget(
            Paragraph.builder().text(Text.from(labelsLine)).alignment(Alignment.CENTER).build(),
            rows.get(2)
        );
    }

    private static int currentIndex(AppState state, List<Step> steps) {
        var phase = state.currentPhase.get();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).phase == phase) return i;
        }
        return 0;
    }

    private static Line buildDotsLine(int width, int currentIdx, boolean error, String spinnerFrame, List<Step> steps) {
        // Layout: dot + 4 bars + dot + 4 bars + ... (one dot per step, bar groups between)
        int n = steps.size();
        int barCount = Math.max(2, Math.min(8, (width - n) / Math.max(1, n - 1)));
        var spans = new ArrayList<Span>();
        for (int i = 0; i < n; i++) {
            spans.add(dotSpan(i, currentIdx, error, spinnerFrame));
            if (i < n - 1) {
                spans.add(barSpan(i, currentIdx, error, barCount));
            }
        }
        return Line.from(spans.toArray(new Span[0]));
    }

    private static Span dotSpan(int idx, int currentIdx, boolean error, String spinnerFrame) {
        if (error && idx == currentIdx) {
            return Span.styled(Icons.CROSS, Style.create().fg(Theme.abort).bold());
        }
        if (idx < currentIdx) {
            return Span.styled(Icons.DOT_FILLED, Style.create().fg(Theme.go).bold());
        }
        if (idx == currentIdx) {
            // Active: alternate between the half-dot and the spinner frame for a
            // subtle pulse without being distracting.
            return Span.styled(Icons.DOT_HALF, Style.create().fg(Theme.fuel).bold());
        }
        return Span.styled(Icons.DOT_EMPTY, Style.create().fg(Theme.subtle));
    }

    private static Span barSpan(int leftIdx, int currentIdx, boolean error, int barCount) {
        var bar = Icons.STEP_BAR.repeat(barCount);
        Color color = leftIdx < currentIdx ? Theme.go : Theme.subtle;
        if (error && leftIdx >= currentIdx) color = Theme.subtle;
        return Span.styled(bar, Style.create().fg(color));
    }

    private static Line buildLabelsLine(int width, int currentIdx, boolean error, List<Step> steps) {
        // We want labels roughly centered under each dot. Easiest: produce one
        // long string with each label slot left-padded; lean on Paragraph CENTER.
        int n = steps.size();
        int barCount = Math.max(2, Math.min(8, (width - n) / Math.max(1, n - 1)));
        int slotWidth = 1 + barCount;
        var spans = new ArrayList<Span>();
        for (int i = 0; i < n; i++) {
            var label = steps.get(i).label;
            var style = labelStyle(i, currentIdx, error);
            // Slot width minus the label, padded left for centering under the dot.
            int leftPad = Math.max(0, (slotWidth - label.length()) / 2);
            int rightPad = Math.max(0, slotWidth - leftPad - label.length());
            if (i == 0) leftPad = Math.max(0, leftPad - 1);
            spans.add(Span.styled(" ".repeat(leftPad), Style.create()));
            spans.add(Span.styled(label, style));
            if (i < n - 1) spans.add(Span.styled(" ".repeat(rightPad), Style.create()));
        }
        return Line.from(spans.toArray(new Span[0]));
    }

    private static Style labelStyle(int idx, int currentIdx, boolean error) {
        if (error && idx == currentIdx) return Styles.error();
        if (idx < currentIdx) return Styles.success();
        if (idx == currentIdx) return Styles.focus();
        return Styles.dim();
    }
}
