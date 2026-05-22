package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.template.GeneratedFile;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class ReviewView implements View {

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var cols = Layout.horizontal()
            .constraints(
                Constraint.percentage(28),   // file list panel
                Constraint.percentage(72)    // content preview panel
            )
            .split(area);

        renderFileList(frame, cols.get(0), state);
        renderPreview(frame, cols.get(1), state);
    }

    private void renderFileList(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.min(0),      // file list
                Constraint.length(4)    // action bar
            )
            .split(area);

        var fileListBlock = Block.builder()
            .title(Title.from(Span.styled(" Generated Files ", Style.create().fg(Color.CYAN))))
            .borders(Borders.ALL)
            .borderStyle(Style.create().fg(Color.CYAN))
            .build();

        var items = state.generatedFiles.stream()
            .map(f -> ListItem.from(f.relativePath()))
            .toList();

        var listState = new ListState();
        listState.select(state.reviewFileIndex);

        var fileList = ListWidget.builder()
            .items(items.toArray(new ListItem[0]))
            .highlightStyle(Style.create().fg(Color.BLACK).bg(Color.YELLOW).bold())
            .highlightSymbol("▶ ")
            .block(fileListBlock)
            .build();

        frame.renderStatefulWidget(fileList, rows.get(0), listState);

        // Action bar - first line shows controls, second shows save status (if any).
        var status = state.reviewSaveStatus.get();
        var statusColor = state.reviewSaveError ? Color.RED : Color.GREEN;
        var actions = Paragraph.builder()
            .text(Text.from(
                Line.from(
                    Span.styled(" s ", Style.create().fg(Color.BLACK).bg(Color.GREEN)),
                    Span.styled(" save all  ", Style.create().fg(Color.DARK_GRAY)),
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

        var kindBadge = kindBadge(current.kind());
        var titleLine = Line.from(
            Span.styled(" " + current.relativePath() + " ", Style.create().fg(Color.WHITE).bold()),
            Span.styled(kindBadge, Style.create().fg(Color.BLACK).bg(Color.CYAN))
        );
        var previewBlock = Block.builder()
            .title(Title.from(titleLine))
            .borders(Borders.ALL)
            .borderStyle(Style.create().fg(Color.CYAN))
            .build();

        var preview = Paragraph.builder()
            .text(Text.from(current.content()))
            .block(previewBlock)
            .overflow(Overflow.WRAP_WORD)
            .build();

        frame.renderWidget(preview, area);
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
        if (key.isChar('s')) {
            saveAllFiles(state);
            return true;
        }
        return false;
    }

    private void saveAllFiles(AppState state) {
        var root = Path.of(state.projectPath).toAbsolutePath();
        int saved = 0;
        String firstError = null;
        for (var file : state.generatedFiles) {
            try {
                var target = root.resolve(file.relativePath());
                var parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(target, file.content());
                saved++;
            } catch (IOException | RuntimeException e) {
                if (firstError == null) {
                    firstError = file.relativePath() + ": " + e.getMessage();
                }
            }
        }

        if (firstError != null) {
            state.reviewSaveError = true;
            state.reviewSaveStatus.set("✗ Saved " + saved + "/" + state.generatedFiles.size()
                + " - error: " + firstError);
        } else {
            state.reviewSaveError = false;
            state.reviewSaveStatus.set("✓ Saved " + saved + " files to " + root);
        }
    }
}
