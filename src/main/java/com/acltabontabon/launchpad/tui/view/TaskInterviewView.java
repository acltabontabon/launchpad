package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.task.TaskTurn;
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
import dev.tamboui.widgets.paragraph.Paragraph;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TaskInterviewView implements View {

    private long tick = 0;

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        tick++;

        var rows = Layout.vertical()
            .constraints(
                Constraint.min(0),       // transcript
                Constraint.length(1),    // gap
                Constraint.length(6),    // question
                Constraint.length(1),    // gap
                Constraint.length(6)     // answer
            )
            .split(area);

        renderTranscript(frame, rows.get(0), state);
        renderQuestion(frame, rows.get(2), state);
        renderAnswerInput(frame, rows.get(4), state);
    }

    private void renderTranscript(Frame frame, Rect area, AppState state) {
        var turns = state.taskTurns.get();

        var header = new ArrayList<Line>();
        header.add(Line.from(Span.styled("Task", Styles.subheading())));
        for (var l : state.taskDescription.split("\n", -1)) {
            header.add(Line.from(Span.styled("  " + l, Styles.body())));
        }
        header.add(Line.from(Span.styled("", Style.create())));

        var turnBlocks = new ArrayList<List<Line>>();
        int i = 1;
        for (var turn : turns) {
            var block = new ArrayList<Line>();
            block.add(Line.from(
                Span.styled("Q" + i + ".  ", Style.create().fg(Theme.fuel).bold()),
                Span.styled(turn.question(), Styles.body())
            ));
            for (var l : turn.answer().split("\n", -1)) {
                block.add(Line.from(Span.styled("    " + l, Styles.muted())));
            }
            block.add(Line.from(Span.styled("", Style.create())));
            turnBlocks.add(block);
            i++;
        }

        int viewportRows = Math.max(1, area.height() - 2);
        var lines = new ArrayList<Line>();

        if (header.size() >= viewportRows) {
            for (int k = 0; k < viewportRows && k < header.size(); k++) lines.add(header.get(k));
        } else {
            lines.addAll(header);
            int remaining = viewportRows - header.size();
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
                    "... " + dropped + " earlier turn" + (dropped == 1 ? "" : "s") + " hidden ...",
                    Styles.caption())));
            }
            for (int k = keepReversed.size() - 1; k >= 0; k--) lines.addAll(keepReversed.get(k));
        }

        var card = Card.of("transcript").build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);
        var transcript = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .overflow(Overflow.WRAP_WORD)
            .build();
        frame.renderWidget(transcript, inner);
    }

    private void renderQuestion(Frame frame, Rect area, AppState state) {
        boolean active = !state.taskError;
        String title = "question  " + Icons.SEP + "  #" + (state.taskRound + 1);
        var card = Card.of(title).active(active).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        Text body;
        if (state.taskError) {
            body = Text.from(Line.from(
                Span.styled(Icons.CROSS + "  ", Styles.error()),
                Span.styled(state.taskStatus.get(), Styles.error())
            ));
        } else if (state.taskThinking) {
            var elapsed = elapsedSuffix(state);
            var msg = state.taskStatus.get().isEmpty() ? "thinking..." : state.taskStatus.get();
            body = Text.from(Line.from(
                Span.styled(Spinner.frame(tick / 3) + "  ", Styles.focus()),
                Span.styled(msg + elapsed, Styles.caption())
            ));
        } else {
            var q = state.taskCurrentQuestion.get();
            body = Text.styled(q.isEmpty() ? "(waiting for first question...)" : q,
                Styles.heading());
        }
        var widget = Paragraph.builder().text(body).overflow(Overflow.WRAP_WORD).build();
        frame.renderWidget(widget, inner);
    }

    private static String elapsedSuffix(AppState state) {
        long start = state.taskOpStartedAtMs;
        if (start == 0L) return "";
        long sec = (System.currentTimeMillis() - start) / 1000;
        return sec <= 0 ? "" : "  " + Icons.SEP + "  " + sec + "s";
    }

    private void renderAnswerInput(Frame frame, Rect area, AppState state) {
        boolean focused = !state.taskThinking && !state.taskCurrentQuestion.get().isEmpty();
        var card = Card.of("your answer").active(focused).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var cursorChar = focused ? "█" : " ";
        var buffer = state.taskCurrentAnswer;
        var bufLines = buffer.isEmpty() ? new String[]{""} : buffer.split("\n", -1);
        var lines = new ArrayList<Line>();
        for (int i = 0; i < bufLines.length; i++) {
            var text = bufLines[i];
            if (i == bufLines.length - 1) {
                lines.add(Line.from(
                    Span.styled(text, Styles.code()),
                    Span.styled(cursorChar, Style.create().fg(Theme.fuel))
                ));
            } else {
                lines.add(Line.from(Span.styled(text, Styles.code())));
            }
        }
        var widget = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .overflow(Overflow.WRAP_WORD)
            .build();
        frame.renderWidget(widget, inner);
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        return List.of(
            new KeyHint("enter", "newline"),
            new KeyHint("tab", "submit"),
            new KeyHint("F1", "finalize"),
            new KeyHint("esc", "cancel")
        );
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (state.taskThinking) {
            if (key.isKey(KeyCode.ESCAPE)) {
                state.cancelRequested = true;
                var fq = state.currentTaskQuestionFuture;
                if (fq != null) fq.cancel(true);
                var ff = state.currentTaskFinalizeFuture;
                if (ff != null) ff.cancel(true);
                state.resetTaskFlow();
                state.currentScreen = AppState.Screen.WELCOME;
                return true;
            }
            return false;
        }

        if (key.isKey(KeyCode.ESCAPE)) {
            state.cancelRequested = true;
            var fq = state.currentTaskQuestionFuture;
            if (fq != null) fq.cancel(true);
            var ff = state.currentTaskFinalizeFuture;
            if (ff != null) ff.cancel(true);
            state.resetTaskFlow();
            state.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        if (key.isKey(KeyCode.F1)) {
            state.taskReadyToFinalize = true;
            state.currentScreen = AppState.Screen.TASK_RESULT;
            return true;
        }
        if (state.taskCurrentQuestion.get().isEmpty()) return false;

        if (key.isKey(KeyCode.TAB)) {
            var answer = state.taskCurrentAnswer.trim();
            if (answer.isEmpty()) return true;
            var current = new ArrayList<>(state.taskTurns.get());
            current.add(new TaskTurn(state.taskCurrentQuestion.get(), answer));
            state.taskTurns.set(current);
            state.taskCurrentAnswer = "";
            state.taskCurrentQuestion.set("");
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
