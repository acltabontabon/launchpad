package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.template.FilePlan;
import com.acltabontabon.launchpad.template.GeneratedFile;
import com.acltabontabon.launchpad.template.LineDiff;
import com.acltabontabon.launchpad.template.WriteService;
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
import dev.tamboui.widgets.list.ListItem;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Review screen - lists generated files with their write-action chips and
 * previews each. Lets the user toggle per-file action (overwrite / merge /
 * skip) and view a unified-style diff before saving.
 */
@Component
public class ReviewView implements View {

    private final WriteService writeService;

    public ReviewView(WriteService writeService) {
        this.writeService = writeService;
    }

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var cols = Layout.horizontal()
            .constraints(
                Constraint.percentage(34),   // file list panel
                Constraint.percentage(66)    // content / diff preview
            )
            .split(area);

        renderFileList(frame, cols.get(0), state);
        renderPreview(frame, cols.get(1), state);
    }

    private void renderFileList(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.min(0),      // file list
                Constraint.length(6)    // action bar
            )
            .split(area);

        var fileListBlock = Block.builder()
            .title(Title.from(Span.styled(" Generated Files ", Style.create().fg(Color.CYAN))))
            .borders(Borders.ALL)
            .borderStyle(Style.create().fg(Color.CYAN))
            .build();

        var items = new ListItem[state.generatedFiles.size()];
        for (int i = 0; i < state.generatedFiles.size(); i++) {
            items[i] = ListItem.from(formatFileRow(state, i));
        }

        var listState = new ListState();
        listState.select(state.reviewFileIndex);

        var fileList = ListWidget.builder()
            .items(items)
            .highlightStyle(Style.create().fg(Color.BLACK).bg(Color.YELLOW).bold())
            .highlightSymbol("▶ ")
            .block(fileListBlock)
            .build();

        frame.renderStatefulWidget(fileList, rows.get(0), listState);

        var status = state.reviewSaveStatus.get();
        var statusColor = state.reviewSaveError ? Color.RED : Color.GREEN;
        var actions = Paragraph.builder()
            .text(Text.from(
                Line.from(
                    Span.styled(" s ", Style.create().fg(Color.BLACK).bg(Color.GREEN)),
                    Span.styled(" save  ", Style.create().fg(Color.DARK_GRAY)),
                    Span.styled(" o ", Style.create().fg(Color.BLACK).bg(Color.CYAN)),
                    Span.styled(" overwrite  ", Style.create().fg(Color.DARK_GRAY)),
                    Span.styled(" m ", Style.create().fg(Color.BLACK).bg(Color.CYAN)),
                    Span.styled(" merge  ", Style.create().fg(Color.DARK_GRAY)),
                    Span.styled(" x ", Style.create().fg(Color.BLACK).bg(Color.CYAN)),
                    Span.styled(" skip", Style.create().fg(Color.DARK_GRAY))
                ),
                Line.from(
                    Span.styled(" d ", Style.create().fg(Color.BLACK).bg(Color.CYAN)),
                    Span.styled(" toggle diff  ", Style.create().fg(Color.DARK_GRAY)),
                    Span.styled(" ↑↓ ", Style.create().fg(Color.BLACK).bg(Color.DARK_GRAY)),
                    Span.styled(" navigate  ", Style.create().fg(Color.DARK_GRAY)),
                    Span.styled(" q ", Style.create().fg(Color.BLACK).bg(Color.RED)),
                    Span.styled(" quit", Style.create().fg(Color.DARK_GRAY))
                ),
                Line.from(Span.styled(status.isEmpty() ? "" : status, Style.create().fg(statusColor).bold()))
            ))
            .block(Block.builder()
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(actions, rows.get(1));
    }

    private static String formatFileRow(AppState state, int idx) {
        var file = state.generatedFiles.get(idx);
        var plan = idx < state.filePlans.size() ? state.filePlans.get(idx) : null;
        var chip = plan == null ? "NEW" : plan.statusChip();
        return file.relativePath() + "  [" + chip + "]";
    }

    private void renderPreview(Frame frame, Rect area, AppState state) {
        var current = state.currentReviewFile();
        if (current == null) {
            var empty = Paragraph.builder()
                .text(Text.styled("No files generated.", Style.create().fg(Color.DARK_GRAY)))
                .block(Block.builder().borders(Borders.ALL).build())
                .build();
            frame.renderWidget(empty, area);
            return;
        }

        var warnings = state.generationWarnings;
        var hasWarnings = warnings != null && !warnings.isEmpty();
        Rect previewArea = area;
        if (hasWarnings) {
            int warnHeight = Math.min(2 + Math.min(warnings.size(), 6), 8);
            var rows = Layout.vertical()
                .constraints(
                    Constraint.length(warnHeight),
                    Constraint.min(0)
                )
                .split(area);
            renderWarningBanner(frame, rows.get(0), warnings);
            previewArea = rows.get(1);
        }

        var plan = state.currentFilePlan();
        if (state.reviewShowDiff && plan != null && plan.exists) {
            renderDiff(frame, previewArea, current, plan);
        } else {
            renderContent(frame, previewArea, current, plan);
        }
    }

    private void renderContent(Frame frame, Rect area, GeneratedFile current, FilePlan plan) {
        var titleSpans = new ArrayList<Span>();
        titleSpans.add(Span.styled(" " + current.relativePath() + " ", Style.create().fg(Color.WHITE).bold()));
        titleSpans.add(Span.styled(kindBadge(current.kind()), Style.create().fg(Color.BLACK).bg(Color.CYAN)));
        if (plan != null) {
            titleSpans.add(Span.styled(" ", Style.create()));
            titleSpans.add(Span.styled(" " + plan.statusChip() + " ", chipStyle(plan.action())));
        }
        var titleLine = Line.from(titleSpans.toArray(new Span[0]));
        var block = Block.builder()
            .title(Title.from(titleLine))
            .borders(Borders.ALL)
            .borderStyle(Style.create().fg(Color.CYAN))
            .build();
        var preview = Paragraph.builder()
            .text(Text.from(current.content()))
            .block(block)
            .overflow(Overflow.WRAP_WORD)
            .build();
        frame.renderWidget(preview, area);
    }

    private void renderDiff(Frame frame, Rect area, GeneratedFile current, FilePlan plan) {
        var entries = LineDiff.diff(plan.existingContent == null ? "" : plan.existingContent,
            plan.resolvedContent());
        // Cap to avoid drowning the pane on huge diffs.
        int maxLines = Math.max(0, area.height() - 2);
        var lines = new ArrayList<Line>();
        int shown = 0;
        for (var e : entries) {
            if (shown >= maxLines) break;
            Style style = switch (e.op()) {
                case ADD -> Style.create().fg(Color.GREEN);
                case REMOVE -> Style.create().fg(Color.RED);
                case KEEP -> Style.create().fg(Color.DARK_GRAY);
            };
            String prefix = switch (e.op()) {
                case ADD -> "+ ";
                case REMOVE -> "- ";
                case KEEP -> "  ";
            };
            lines.add(Line.from(Span.styled(prefix + e.line(), style)));
            shown++;
        }
        if (entries.size() > shown) {
            lines.add(Line.from(Span.styled("... (+" + (entries.size() - shown) + " more lines)",
                Style.create().fg(Color.DARK_GRAY))));
        }
        var titleLine = Line.from(
            Span.styled(" diff: " + current.relativePath() + " ", Style.create().fg(Color.WHITE).bold()),
            Span.styled(" " + plan.statusChip() + " ", chipStyle(plan.action()))
        );
        var block = Block.builder()
            .title(Title.from(titleLine))
            .borders(Borders.ALL)
            .borderStyle(Style.create().fg(Color.YELLOW))
            .build();
        var widget = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .block(block)
            .build();
        frame.renderWidget(widget, area);
    }

    private static Style chipStyle(FilePlan.Action action) {
        return switch (action) {
            case WRITE_NEW -> Style.create().fg(Color.BLACK).bg(Color.GREEN);
            case OVERWRITE -> Style.create().fg(Color.BLACK).bg(Color.RED);
            case MERGE -> Style.create().fg(Color.BLACK).bg(Color.YELLOW);
            case SKIP -> Style.create().fg(Color.WHITE).bg(Color.DARK_GRAY);
        };
    }

    private void renderWarningBanner(Frame frame, Rect area, List<String> warnings) {
        var lines = new ArrayList<Line>();
        lines.add(Line.from(Span.styled(
            " ⚠ " + warnings.size() + " generation warning" + (warnings.size() == 1 ? "" : "s"),
            Style.create().fg(Color.BLACK).bg(Color.YELLOW).bold())));
        int limit = Math.min(warnings.size(), 6);
        for (int i = 0; i < limit; i++) {
            lines.add(Line.from(Span.styled(" • " + warnings.get(i),
                Style.create().fg(Color.YELLOW))));
        }
        var banner = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .block(Block.builder()
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(Color.YELLOW))
                .build())
            .overflow(Overflow.WRAP_WORD)
            .build();
        frame.renderWidget(banner, area);
    }

    private String kindBadge(GeneratedFile.FileKind kind) {
        return switch (kind) {
            case CONTEXT -> " CONTEXT ";
            case RULES   -> " RULES ";
            case SKILL   -> " SKILL ";
            case INDEX   -> " INDEX ";
        };
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            state.nextReviewFile();
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            state.prevReviewFile();
            return true;
        }
        if (key.isChar('o')) {
            setActionForCurrent(state, FilePlan.Action.OVERWRITE);
            return true;
        }
        if (key.isChar('m')) {
            setActionForCurrent(state, FilePlan.Action.MERGE);
            return true;
        }
        if (key.isChar('x')) {
            setActionForCurrent(state, FilePlan.Action.SKIP);
            return true;
        }
        if (key.isChar('d')) {
            state.reviewShowDiff = !state.reviewShowDiff;
            return true;
        }
        if (key.isChar('s')) {
            applyPlans(state);
            return true;
        }
        return false;
    }

    private void setActionForCurrent(AppState state, FilePlan.Action action) {
        var plan = state.currentFilePlan();
        if (plan == null) return;
        // Honour the intent verbatim: 'o' on a non-existing file becomes WRITE_NEW
        // (functionally the same write), 'o' on an existing file becomes OVERWRITE.
        if (action == FilePlan.Action.OVERWRITE && !plan.exists) {
            plan.setAction(FilePlan.Action.WRITE_NEW);
        } else {
            plan.setAction(action);
        }
    }

    private void applyPlans(AppState state) {
        var root = Path.of(state.projectPath).toAbsolutePath();
        try {
            var result = writeService.apply(root, state.filePlans);
            if (!result.errors().isEmpty()) {
                state.reviewSaveError = true;
                state.reviewSaveStatus.set("✗ " + result.written() + " written, "
                    + result.errors().size() + " error(s): " + result.errors().get(0));
                return;
            }
            state.reviewSaveError = false;
            var msg = new StringBuilder("✓ ").append(result.written()).append(" written");
            if (result.skipped() > 0) msg.append(", ").append(result.skipped()).append(" skipped");
            if (result.backedUp() > 0) msg.append(", ").append(result.backedUp())
                .append(" backed up to ").append(root.relativize(result.backupDir()));
            state.reviewSaveStatus.set(msg.toString());
        } catch (Exception e) {
            state.reviewSaveError = true;
            state.reviewSaveStatus.set("✗ Save failed: " + e.getMessage());
        }
    }
}
