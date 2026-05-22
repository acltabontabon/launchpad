package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.task.TaskTurn;
import com.acltabontabon.launchpad.tui.AppState;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
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
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Conversational Q&A. The LLM call itself is fired from LaunchpadRunner so this
 * view stays pure - the view just reflects state set by the background task and
 * captures the user's answer when they press Enter.
 */
@Component
public class TaskInterviewView implements View {

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private int spinnerFrame = 0;

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        spinnerFrame = (spinnerFrame + 1) % SPINNER.length;

        var rows = Layout.vertical()
            .constraints(
                Constraint.min(0),       // transcript - takes most of the screen
                Constraint.length(6),    // current question - fits a wrapped question
                Constraint.length(6),    // answer input - 4 lines of text + borders
                Constraint.length(1)     // hints
            )
            .split(area);

        renderTranscript(frame, rows.get(0), state);
        renderQuestion(frame, rows.get(1), state);
        renderAnswerInput(frame, rows.get(2), state);
        renderHints(frame, rows.get(3), state);
    }

    private void renderTranscript(Frame frame, Rect area, AppState state) {
        var turns = state.taskTurns.get();

        // Task header (always pinned at the top of the box).
        var header = new ArrayList<Line>();
        header.add(Line.from(Span.styled(" Task: ",
            Style.create().fg(Color.CYAN).bold())));
        for (var l : state.taskDescription.split("\n", -1)) {
            header.add(Line.from(Span.styled("   " + l, Style.create().fg(Color.WHITE))));
        }
        header.add(Line.from(Span.styled("", Style.create())));

        // Render each Q/A pair as a complete block so we can keep them together.
        var turnBlocks = new ArrayList<List<Line>>();
        int i = 1;
        for (var turn : turns) {
            var block = new ArrayList<Line>();
            block.add(Line.from(Span.styled(" Q" + i + ".  " + turn.question(),
                Style.create().fg(Color.YELLOW))));
            for (var l : turn.answer().split("\n", -1)) {
                block.add(Line.from(Span.styled("     " + l, Style.create().fg(Color.WHITE))));
            }
            block.add(Line.from(Span.styled("", Style.create())));
            turnBlocks.add(block);
            i++;
        }

        // Available rows inside the box (subtract top/bottom border rows).
        int viewportRows = Math.max(1, area.height() - 2);
        var lines = new ArrayList<Line>();

        if (header.size() >= viewportRows) {
            // Tiny box - just show whatever fits of the header.
            for (int k = 0; k < viewportRows && k < header.size(); k++) lines.add(header.get(k));
        } else {
            lines.addAll(header);
            int remaining = viewportRows - header.size();

            // Walk turn blocks from newest to oldest, collecting whole blocks that
            // still fit. Reserve 1 row for an "older turns hidden" marker if we
            // end up dropping any. This guarantees we never show an answer without
            // its question.
            int totalTurnRows = turnBlocks.stream().mapToInt(List::size).sum();
            boolean willTruncate = totalTurnRows > remaining;
            int budget = willTruncate ? Math.max(0, remaining - 1) : remaining;

            var keepReversed = new ArrayList<List<Line>>();
            int used = 0;
            for (int k = turnBlocks.size() - 1; k >= 0; k--) {
                var block = turnBlocks.get(k);
                if (used + block.size() > budget) break;
                keepReversed.add(block);
                used += block.size();
            }

            int dropped = turnBlocks.size() - keepReversed.size();
            if (dropped > 0) {
                lines.add(Line.from(Span.styled(
                    " ... " + dropped + " earlier turn" + (dropped == 1 ? "" : "s") + " hidden ...",
                    Style.create().fg(Color.DARK_GRAY).italic())));
            }
            // Append the kept blocks in chronological order.
            for (int k = keepReversed.size() - 1; k >= 0; k--) lines.addAll(keepReversed.get(k));
        }

        var transcript = Paragraph.builder()
            .text(Text.from(lines))
            .overflow(Overflow.WRAP_WORD)
            .block(Block.builder()
                .title(Title.from(Span.styled(" Transcript ", Style.create().fg(Color.DARK_GRAY))))
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(transcript, area);
    }

    private void renderQuestion(Frame frame, Rect area, AppState state) {
        Color frameColor;
        String title = " Question #" + (state.taskRound + 1) + " ";

        Text body;
        if (state.taskError) {
            frameColor = Color.RED;
            body = Text.from(Line.from(
                Span.styled(" ✗  ", Style.create().fg(Color.RED).bold()),
                Span.styled(state.taskStatus.get(), Style.create().fg(Color.RED))
            ));
        } else if (state.taskThinking) {
            frameColor = Color.YELLOW;
            var elapsed = elapsedSuffix(state);
            body = Text.from(Line.from(
                Span.styled(" " + SPINNER[spinnerFrame] + "  ", Style.create().fg(Color.CYAN)),
                Span.styled((state.taskStatus.get().isEmpty() ? "thinking..." : state.taskStatus.get()) + elapsed,
                    Style.create().fg(Color.DARK_GRAY).italic())
            ));
        } else {
            frameColor = Color.YELLOW;
            var q = state.taskCurrentQuestion.get();
            body = Text.styled(" " + (q.isEmpty() ? "(waiting for first question...)" : q),
                Style.create().fg(Color.WHITE).bold());
        }

        var box = Paragraph.builder()
            .text(body)
            .overflow(Overflow.WRAP_WORD)
            .block(Block.builder()
                .title(Title.from(Span.styled(title, Style.create().fg(frameColor))))
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(frameColor))
                .build())
            .build();
        frame.renderWidget(box, area);
    }

    private static String elapsedSuffix(AppState state) {
        long start = state.taskOpStartedAtMs;
        if (start == 0L) return "";
        long sec = (System.currentTimeMillis() - start) / 1000;
        return sec <= 0 ? "" : "  (" + sec + "s)";
    }

    private void renderAnswerInput(Frame frame, Rect area, AppState state) {
        boolean focused = !state.taskThinking && !state.taskCurrentQuestion.get().isEmpty();
        var borderColor = focused ? Color.CYAN : Color.DARK_GRAY;
        var cursorChar = focused ? "█" : " ";

        // Multi-line buffer: split on `\n` and render each line as a separate
        // Line. Cursor block goes after the last line's text.
        var buffer = state.taskCurrentAnswer;
        var bufLines = buffer.isEmpty() ? new String[]{""} : buffer.split("\n", -1);
        var lines = new ArrayList<Line>();
        for (int i = 0; i < bufLines.length; i++) {
            var text = bufLines[i];
            if (i == bufLines.length - 1) {
                lines.add(Line.from(
                    Span.styled(" " + text, Style.create().fg(Color.WHITE)),
                    Span.styled(cursorChar, Style.create().fg(Color.WHITE))
                ));
            } else {
                lines.add(Line.from(Span.styled(" " + text, Style.create().fg(Color.WHITE))));
            }
        }

        var box = Paragraph.builder()
            .text(Text.from(lines))
            .overflow(Overflow.WRAP_WORD)
            .block(Block.builder()
                .title(Title.from(Span.styled(" Your answer ",
                    Style.create().fg(borderColor))))
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(borderColor))
                .build())
            .build();
        frame.renderWidget(box, area);
    }

    private void renderHints(Frame frame, Rect area, AppState state) {
        var hints = Paragraph.builder()
            .text(Text.from(Line.from(
                Span.styled(" Enter ", Style.create().fg(Color.BLACK).bg(Color.DARK_GRAY)),
                Span.styled(" newline  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" Tab ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" submit answer  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" F1 ", Style.create().fg(Color.BLACK).bg(Color.YELLOW)),
                Span.styled(" finalize now  ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(" Esc ", Style.create().fg(Color.BLACK).bg(Color.DARK_GRAY)),
                Span.styled(" cancel", Style.create().fg(Color.DARK_GRAY))
            )))
            .build();
        frame.renderWidget(hints, area);
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        // Input is locked while the model is thinking, except for the cancel escape hatch.
        if (state.taskThinking) {
            if (key.isKey(KeyCode.ESCAPE)) {
                state.resetTaskFlow();
                state.currentScreen = AppState.Screen.WELCOME;
                return true;
            }
            return false;
        }

        if (key.isKey(KeyCode.ESCAPE)) {
            state.resetTaskFlow();
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        // F1 to finalize early. Avoids stealing a printable character from the
        // user's freeform answer.
        if (key.isKey(KeyCode.F1)) {
            state.taskReadyToFinalize = true;
            state.currentScreen = AppState.Screen.TASK_RESULT;
            return true;
        }

        // Don't accept answer input until we actually have a question on screen.
        if (state.taskCurrentQuestion.get().isEmpty()) return false;

        // Tab submits the (possibly multi-line) answer. ENTER inserts a newline
        // so users can structure longer answers. Mirrors TaskInputView's pattern.
        if (key.isKey(KeyCode.TAB)) {
            var answer = state.taskCurrentAnswer.trim();
            if (answer.isEmpty()) return true;
            var current = new ArrayList<>(state.taskTurns.get());
            current.add(new TaskTurn(state.taskCurrentQuestion.get(), answer));
            state.taskTurns.set(current);
            state.taskCurrentAnswer = "";
            state.taskCurrentQuestion.set("");  // signal Runner to fetch the next question
            state.taskRound = state.taskRound + 1;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            state.taskCurrentAnswer = state.taskCurrentAnswer + "\n";
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            if (!state.taskCurrentAnswer.isEmpty()) {
                state.taskCurrentAnswer =
                    state.taskCurrentAnswer.substring(0, state.taskCurrentAnswer.length() - 1);
            }
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            state.taskCurrentAnswer = state.taskCurrentAnswer + key.character();
            return true;
        }
        return false;
    }
}
