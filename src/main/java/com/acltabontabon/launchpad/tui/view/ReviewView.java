package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.template.FilePlan;
import com.acltabontabon.launchpad.template.GeneratedFile;
import com.acltabontabon.launchpad.template.LineDiff;
import com.acltabontabon.launchpad.template.WriteService;
import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.Chip;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.theme.Icons;
import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.markdown.MarkdownView;
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
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.list.ListItem;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class ReviewView implements View {

    private final WriteService writeService;

    public ReviewView(WriteService writeService) {
        this.writeService = writeService;
    }

    private static final int WARN_VISIBLE_CAP = 4;

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        // Layout: save status at the top (latest action), file list / preview
        // in the middle (the real content), warnings at the bottom (persistent
        // reference info, low-attention zone next to the footer).
        var warnings = state.gen.warnings;
        var hasWarnings = warnings != null && !warnings.isEmpty();
        var status = state.gen.saveStatus.get();
        var hasStatus = status != null && !status.isEmpty();

        int statusRows = hasStatus ? 2 : 0;
        int warnVisible = hasWarnings
            ? Math.min(warnings.size(), WARN_VISIBLE_CAP)
            : 0;
        // Bottom warning block: 1 breathing row (top) + 1 header + N bullets + 1 overflow.
        int warnRows = hasWarnings ? warnVisible + 3 : 0;

        var rows = Layout.vertical()
            .constraints(
                Constraint.length(statusRows),
                Constraint.min(0),
                Constraint.length(warnRows)
            )
            .split(area);

        if (hasStatus) renderSaveStatusStrip(frame, rows.get(0), status, state.gen.saveError);
        if (hasWarnings) renderWarningStrip(frame, rows.get(2), warnings);

        var bodyArea = rows.get(1);
        var cols = Layout.horizontal()
            .constraints(
                Constraint.percentage(36),
                Constraint.length(1),
                Constraint.min(0)
            )
            .split(bodyArea);

        renderFileList(frame, cols.get(0), state);
        renderPreview(frame, cols.get(2), state);
    }

    private void renderSaveStatusStrip(Frame frame, Rect area, String status, boolean isError) {
        // 2-row area: line 1 = status, line 2 = breathing gap
        var lineArea = new Rect(area.x(), area.y(), area.width(), 1);
        var style = isError ? Styles.error() : Styles.success();
        var p = Paragraph.builder()
            .text(Text.from(Line.from(Span.styled("  " + status, style))))
            .build();
        frame.renderWidget(p, lineArea);
    }

    private void renderFileList(Frame frame, Rect area, AppState state) {
        int n = state.gen.files.size();
        var card = Card.of("files  " + Icons.SEP + "  " + n).active(true).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var items = new ListItem[n];
        for (int i = 0; i < n; i++) {
            items[i] = ListItem.from(buildFileRow(state, i, inner.width()));
        }

        var listState = new ListState();
        listState.select(state.gen.fileIndex);

        var fileList = ListWidget.builder()
            .items(items)
            .highlightStyle(Styles.listHighlight())
            .highlightSymbol(Icons.CURSOR + " ")
            .build();

        frame.renderStatefulWidget(fileList, inner, listState);
    }

    private static Line buildFileRow(AppState state, int idx, int width) {
        var file = state.gen.files.get(idx);
        var plan = idx < state.gen.plans.size() ? state.gen.plans.get(idx) : null;
        var path = file.relativePath();
        boolean wasSaved = state.gen.savedFileIndices.contains(idx);
        var chip = wasSaved ? "SAVED" : (plan == null ? "NEW" : plan.statusChip());
        var chipStyle = wasSaved
            ? Styles.successChip()
            : (plan == null ? Styles.successChip() : chipForAction(plan.action()));

        int reserved = 2 + chip.length() + 2; // "▶ " width handled by ListWidget
        int pathWidth = Math.max(0, width - reserved - 4);
        var displayedPath = path.length() <= pathWidth ? path
            : "…" + path.substring(path.length() - pathWidth + 1);
        int pad = Math.max(1, pathWidth - displayedPath.length() + 1);

        return Line.from(
            Span.styled(displayedPath, Styles.body()),
            Span.styled(" ".repeat(pad), Style.create()),
            Chip.of(chip, chipStyle)
        );
    }

    private void renderPreview(Frame frame, Rect area, AppState state) {
        var current = state.gen.currentFile();
        if (current == null) {
            var card = Card.of("preview").build();
            var inner = card.inner(area);
            frame.renderWidget(card, area);
            var empty = Paragraph.builder()
                .text(Text.from(Line.from(Span.styled("No files generated.", Styles.caption()))))
                .build();
            frame.renderWidget(empty, inner);
            return;
        }
        var plan = state.gen.currentPlan();
        if (plan != null && plan.action() == FilePlan.Action.UNREADABLE) {
            renderUnreadable(frame, area, current, plan);
            return;
        }
        if (state.gen.showDiff) {
            // NEW files have no on-disk baseline, so a real diff is empty. Render the
            // whole file as additions instead - useful preview, and the `d` key
            // actually does something visible.
            if (plan == null || !plan.exists) {
                renderAllAddDiff(frame, area, current, plan);
            } else {
                renderDiff(frame, area, current, plan);
            }
        } else {
            renderContent(frame, area, current, plan, state);
        }
    }

    private void renderUnreadable(Frame frame, Rect area, GeneratedFile current, FilePlan plan) {
        var bottom = "unreadable  " + Icons.SEP + "  " + plan.statusChip();
        var card = Card.of(current.relativePath()).bottomTitle(bottom).active(true).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var lines = new ArrayList<Line>();
        lines.add(Line.from(Span.styled("", Style.create())));
        lines.add(Line.from(
            Span.styled("  " + Icons.WARN + "  ", Styles.error()),
            Span.styled("Existing file could not be read.",
                Style.create().fg(Theme.caution).bold())
        ));
        lines.add(Line.from(Span.styled("", Style.create())));
        lines.add(Line.from(
            Span.styled("  cause: ", Styles.dim()),
            Span.styled(plan.errorMessage == null ? "(no detail)" : plan.errorMessage, Styles.body())
        ));
        lines.add(Line.from(Span.styled("", Style.create())));
        lines.add(Line.from(Span.styled(
            "  Resolve the underlying issue (permissions, file lock) and re-run,"
                + " or press 'x' to skip.", Styles.muted())));
        var widget = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .overflow(Overflow.WRAP_WORD)
            .build();
        frame.renderWidget(widget, inner);
    }

    private void renderContent(Frame frame, Rect area, GeneratedFile current, FilePlan plan, AppState state) {
        var bottom = buildBottomTitle(current, plan);
        var card = Card.of(current.relativePath())
            .bottomTitle(bottom)
            .active(true)
            .build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var content = current.content() == null ? "" : current.content();
        if (isMarkdownFile(current.relativePath())) {
            // Clamp the scroll offset so PgUp/PgDn can't push the view past the
            // last line of the document.
            var probe = MarkdownView.builder().source(content).overflow(Overflow.WRAP_WORD).build();
            int total = probe.computeHeight(inner.width());
            int maxScroll = Math.max(0, total - inner.height());
            int scroll = Math.min(state.gen.previewScroll, maxScroll);
            state.gen.previewScroll = scroll;
            var preview = MarkdownView.builder()
                .source(content)
                .overflow(Overflow.WRAP_WORD)
                .scroll(scroll)
                .build();
            frame.renderWidget(preview, inner);
        } else {
            var preview = Paragraph.builder()
                .text(Text.from(content))
                .overflow(Overflow.WRAP_WORD)
                .build();
            frame.renderWidget(preview, inner);
        }
    }

    private static boolean isMarkdownFile(String relativePath) {
        return relativePath != null && relativePath.toLowerCase().endsWith(".md");
    }

    private void renderAllAddDiff(Frame frame, Rect area, GeneratedFile current, FilePlan plan) {
        var planChip = plan == null ? "NEW" : plan.statusChip();
        var bottom = "diff  " + Icons.SEP + "  all new  " + Icons.SEP + "  " + planChip;
        var card = Card.of(current.relativePath()).bottomTitle(bottom).active(true).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        var raw = current.content() == null ? "" : current.content();
        var allLines = raw.split("\n", -1);
        int maxLines = Math.max(0, inner.height());
        var lines = new ArrayList<Line>();
        int shown = 0;
        for (var raw1 : allLines) {
            if (shown >= maxLines - 1) break;
            lines.add(Line.from(Span.styled("+ " + raw1, Styles.diffAdd())));
            shown++;
        }
        if (allLines.length > shown) {
            lines.add(Line.from(Span.styled(
                "  ... +" + (allLines.length - shown) + " more lines", Styles.caption())));
        }
        var widget = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(widget, inner);
    }

    private void renderDiff(Frame frame, Rect area, GeneratedFile current, FilePlan plan) {
        var entries = LineDiff.diff(plan.existingContent == null ? "" : plan.existingContent,
            plan.resolvedContent());
        var bottom = "diff  " + Icons.SEP + "  " + plan.statusChip();
        var card = Card.of(current.relativePath())
            .bottomTitle(bottom)
            .active(true)
            .build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        int maxLines = Math.max(0, inner.height());
        var lines = new ArrayList<Line>();
        int shown = 0;
        for (var e : entries) {
            if (shown >= maxLines - 1) break;
            Style style = switch (e.op()) {
                case ADD -> Styles.diffAdd();
                case REMOVE -> Styles.diffRemove();
                case KEEP -> Styles.diffContext();
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
            lines.add(Line.from(Span.styled(
                "  ... +" + (entries.size() - shown) + " more lines", Styles.caption())));
        }
        var widget = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(widget, inner);
    }

    private static String buildBottomTitle(GeneratedFile current, FilePlan plan) {
        var sb = new StringBuilder();
        sb.append(kindLabel(current.kind()));
        sb.append("  ").append(Icons.SEP).append("  ").append(byteCount(current.content()));
        if (plan != null) sb.append("  ").append(Icons.SEP).append("  ").append(plan.statusChip());
        return sb.toString();
    }

    private static String kindLabel(GeneratedFile.FileKind kind) {
        return switch (kind) {
            case CONTEXT -> "context";
            case RULES -> "rules";
            case SKILL -> "skill";
            case SKILLS -> "skills";
            case INDEX -> "index";
            case CONFIG -> "config";
        };
    }

    private static String byteCount(String content) {
        if (content == null) return "0 B";
        int bytes = content.length();
        if (bytes < 1024) return bytes + " B";
        return String.format("%.1f KB", bytes / 1024.0);
    }

    private static Style chipForAction(FilePlan.Action action) {
        return switch (action) {
            case WRITE_NEW -> Styles.successChip();
            case OVERWRITE -> Styles.dangerChip();
            case MERGE -> Styles.cautionChip();
            case SKIP -> Styles.muteChip();
            case CORRUPTED -> Styles.dangerChip();
            case UNREADABLE -> Styles.dangerChip();
        };
    }

    private void renderWarningStrip(Frame frame, Rect area, List<String> warnings) {
        int n = warnings.size();
        int shown = Math.min(n, WARN_VISIBLE_CAP);

        var lines = new ArrayList<Line>();
        // Leading blank so the strip doesn't slam against the body card's
        // bottom border when the panel sits at the bottom of the screen.
        lines.add(Line.from(Span.styled("", Style.create())));
        // Header: ⚠ N warnings
        lines.add(Line.from(
            Span.styled("  " + Icons.WARN + "  ", Styles.warning()),
            Span.styled(n + " warning" + (n == 1 ? "" : "s"),
                Style.create().fg(Theme.caution).bold())
        ));
        // One bullet line per warning, capped.
        for (int i = 0; i < shown; i++) {
            lines.add(Line.from(
                Span.styled("       " + Icons.BULLET + "  ", Styles.dim()),
                Span.styled(warnings.get(i), Styles.muted())
            ));
        }
        if (n > shown) {
            lines.add(Line.from(Span.styled(
                "       " + Icons.BULLET + "  +" + (n - shown) + " more",
                Styles.caption())));
        }
        var widget = Paragraph.builder()
            .text(Text.from(lines.toArray(new Line[0])))
            .build();
        frame.renderWidget(widget, area);
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        return List.of(
            new KeyHint("↑↓", "navigate"),
            new KeyHint("o", "overwrite"),
            new KeyHint("m", "merge"),
            new KeyHint("x", "skip"),
            new KeyHint("d", "diff"),
            new KeyHint("pgup/pgdn", "scroll"),
            new KeyHint("s", "save all"),
            new KeyHint("esc", "home")
        );
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (event instanceof MouseEvent mouse) {
            // Route scroll-wheel events to the preview pane only.
            if (mouse.kind() == dev.tamboui.tui.event.MouseEventKind.SCROLL_DOWN) {
                state.gen.previewScroll = state.gen.previewScroll + 3;
                return true;
            }
            if (mouse.kind() == dev.tamboui.tui.event.MouseEventKind.SCROLL_UP) {
                state.gen.previewScroll = Math.max(0, state.gen.previewScroll - 3);
                return true;
            }
            return false;
        }
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isKey(KeyCode.ESCAPE)) {
            state.resetReviewFlow();
            state.nav.currentScreen = AppState.Screen.WELCOME;
            return true;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            state.gen.nextFile();
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            state.gen.prevFile();
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
            state.gen.showDiff = !state.gen.showDiff;
            state.gen.previewScroll = 0;
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            // Step is clamped at render time, so a generous jump here just gets
            // capped against the document length.
            state.gen.previewScroll = state.gen.previewScroll + 10;
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            state.gen.previewScroll = Math.max(0, state.gen.previewScroll - 10);
            return true;
        }
        if (key.isChar('s')) {
            applyPlans(state);
            return true;
        }
        return false;
    }

    private void setActionForCurrent(AppState state, FilePlan.Action action) {
        var plan = state.gen.currentPlan();
        if (plan == null) return;
        // Markers are broken; MERGE would either throw or silently rewrite around
        // the corruption. Force the user to choose OVERWRITE or SKIP.
        if (plan.action() == FilePlan.Action.CORRUPTED && action == FilePlan.Action.MERGE) {
            return;
        }
        // We never read the existing content, so we cannot safely OVERWRITE or
        // MERGE without risking clobbering hand-edited data. Only SKIP is allowed
        // until the user fixes the underlying read failure and re-runs.
        if (plan.action() == FilePlan.Action.UNREADABLE && action != FilePlan.Action.SKIP) {
            return;
        }
        if (action == FilePlan.Action.OVERWRITE && !plan.exists) {
            plan.setAction(FilePlan.Action.WRITE_NEW);
        } else {
            plan.setAction(action);
        }
    }

    private void applyPlans(AppState state) {
        var root = Path.of(state.projectPath).toAbsolutePath();
        try {
            var result = writeService.apply(root, state.gen.plans);

            // Derive the set of indices that were actually written.
            var failedPaths = new java.util.HashSet<String>();
            for (var err : result.errors()) {
                int colon = err.indexOf(':');
                if (colon > 0) failedPaths.add(err.substring(0, colon));
            }
            for (int i = 0; i < state.gen.plans.size(); i++) {
                var plan = state.gen.plans.get(i);
                if (plan.action() == com.acltabontabon.launchpad.template.FilePlan.Action.SKIP
                    || plan.action() == com.acltabontabon.launchpad.template.FilePlan.Action.CORRUPTED
                    || plan.action() == com.acltabontabon.launchpad.template.FilePlan.Action.UNREADABLE) continue;
                if (failedPaths.contains(plan.file.relativePath())) continue;
                state.gen.savedFileIndices.add(i);
            }

            if (!result.errors().isEmpty()) {
                state.gen.saveError = true;
                state.gen.saveStatus.set(Icons.CROSS + " " + result.written() + " written, "
                    + result.errors().size() + " error(s): " + result.errors().get(0));
                return;
            }
            state.gen.saveError = false;
            var msg = new StringBuilder(Icons.CHECK + " ").append(result.written()).append(" written");
            if (result.skipped() > 0) msg.append(", ").append(result.skipped()).append(" skipped");
            if (result.backedUp() > 0) msg.append(", ").append(result.backedUp())
                .append(" backed up to ").append(root.relativize(result.backupDir()));
            state.gen.saveStatus.set(msg.toString());
        } catch (Exception e) {
            state.gen.saveError = true;
            state.gen.saveStatus.set(Icons.CROSS + " Save failed: " + e.getMessage());
        }
    }
}
