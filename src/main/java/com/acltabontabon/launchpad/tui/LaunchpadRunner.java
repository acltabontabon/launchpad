package com.acltabontabon.launchpad.tui;

import com.acltabontabon.launchpad.ai.ContextGeneratorService;
import com.acltabontabon.launchpad.ai.OllamaHealthChecker;
import com.acltabontabon.launchpad.ai.OllamaStatus;
import com.acltabontabon.launchpad.scanner.ProjectScanner;
import com.acltabontabon.launchpad.template.ContextTemplateEngine;
import com.acltabontabon.launchpad.tui.view.ProjectSelectView;
import com.acltabontabon.launchpad.tui.view.ReviewView;
import com.acltabontabon.launchpad.tui.view.ScanProgressView;
import com.acltabontabon.launchpad.tui.view.TargetSelectView;
import com.acltabontabon.launchpad.tui.view.View;
import com.acltabontabon.launchpad.tui.view.WelcomeView;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Component
public class LaunchpadRunner implements ApplicationRunner {

    private static final String HEADER_TITLE = " ◆ LAUNCHPAD - AI Context Generator ";
    private static final String[] STEP_LABELS = {"Welcome", "Project", "Target", "Scanning", "Review"};

    private final AppState state = new AppState();

    private final WelcomeView welcomeView;
    private final ProjectSelectView projectSelectView;
    private final TargetSelectView targetSelectView;
    private final ScanProgressView scanProgressView;
    private final ReviewView reviewView;

    private final ProjectScanner scanner;
    private final ContextGeneratorService generatorService;
    private final OllamaHealthChecker healthChecker;
    private final ContextTemplateEngine templateEngine = new ContextTemplateEngine();

    private boolean scanStarted = false;

    public LaunchpadRunner(
        WelcomeView welcomeView,
        ProjectSelectView projectSelectView,
        TargetSelectView targetSelectView,
        ScanProgressView scanProgressView,
        ReviewView reviewView,
        ProjectScanner scanner,
        ContextGeneratorService generatorService,
        OllamaHealthChecker healthChecker
    ) {
        this.welcomeView = welcomeView;
        this.projectSelectView = projectSelectView;
        this.targetSelectView = targetSelectView;
        this.scanProgressView = scanProgressView;
        this.reviewView = reviewView;
        this.scanner = scanner;
        this.generatorService = generatorService;
        this.healthChecker = healthChecker;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var config = TuiConfig.builder()
            .tickRate(Duration.ofMillis(80))  // ~12fps - enough for smooth spinner
            .build();
        try (var tui = TuiRunner.create(config)) {
            tui.run(this::handleEvent, this::renderFrame);
        }
    }

    // ── Event handling ────────────────────────────────────────────────────────

    private boolean handleEvent(Event event, TuiRunner runner) {
        // Global quit - q from any screen except text input
        if (event instanceof KeyEvent key
                && key.isChar('q')
                && state.currentScreen != AppState.Screen.PROJECT_SELECT) {
            runner.quit();
            return true;
        }
        return currentView().handleEvent(event, runner, state);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void renderFrame(Frame frame) {
        triggerHealthCheckIfNeeded();
        triggerScanIfNeeded();

        var area = frame.area();
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(3),   // header
                Constraint.min(0),      // content
                Constraint.length(3)    // footer
            )
            .split(area);

        renderHeader(frame, rows.get(0));
        currentView().render(frame, rows.get(1), state);
        renderFooter(frame, rows.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        var stepIndex = state.currentScreen.ordinal();

        var tabsState = new TabsState(stepIndex);
        var tabs = Tabs.builder()
            .titles(STEP_LABELS)
            .block(Block.builder()
                .title(Title.from(Span.styled(HEADER_TITLE, Style.create().fg(Color.CYAN).bold())))
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(Color.CYAN))
                .build())
            .highlightStyle(Style.create().fg(Color.YELLOW).bold())
            .divider("|")
            .build();

        frame.renderStatefulWidget(tabs, area, tabsState);
    }

    private void renderFooter(Frame frame, Rect area) {
        var projectInfo = state.projectPath.isEmpty()
            ? "No project selected"
            : state.projectPath + " → " + state.selectedTarget.displayName;

        var footer = Paragraph.builder()
            .text(Text.from(Line.from(
                Span.styled(" " + projectInfo, Style.create().fg(Color.DARK_GRAY))
            )))
            .block(Block.builder()
                .borders(Borders.ALL)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(footer, area);
    }

    // ── Background health check ────────────────────────────────────────────────

    private void triggerHealthCheckIfNeeded() {
        if (!state.healthCheckRequested) return;
        state.healthCheckRequested = false;
        state.ollamaStatus.set(OllamaStatus.checking());

        CompletableFuture.runAsync(() -> state.ollamaStatus.set(healthChecker.check()));
    }

    // ── Background scan pipeline ───────────────────────────────────────────────

    /**
     * Triggered once when the user enters the SCANNING screen.
     * Runs the full pipeline in a background thread, updating AppState for the TUI to read.
     */
    private void triggerScanIfNeeded() {
        if (state.currentScreen != AppState.Screen.SCANNING || scanStarted) return;
        scanStarted = true;

        CompletableFuture.runAsync(() -> {
            try {
                // Phase 1 - file scan
                state.scanProgress.set(5);
                state.scanMessage.set("Scanning project files...");
                var ctx = scanner.scan(state.projectPath, msg -> {
                    state.scanMessage.set(msg);
                });

                // Phase 2 - AI: project summary
                state.scanProgress.set(30);
                state.scanMessage.set("Generating project summary with local AI...");
                var summary = generatorService.generateProjectSummary(ctx);

                // Phase 3 - AI: target-specific content
                state.scanProgress.set(60);
                state.scanMessage.set("Generating " + state.selectedTarget.displayName + " content...");
                var targetContent = generatorService.generateTargetSpecificContent(ctx, state.selectedTarget);

                // Phase 4 - assemble files
                state.scanProgress.set(85);
                state.scanMessage.set("Assembling output files...");
                var files = templateEngine.buildFiles(ctx, state.selectedTarget, summary, targetContent);
                state.generatedFiles = files;

                // Done
                state.scanProgress.set(100);
                state.scanMessage.set("Done! " + files.size() + " files generated.");
                state.scanComplete = true;

            } catch (Exception e) {
                state.scanError = true;
                state.scanMessage.set("Error: " + e.getMessage());
                state.scanComplete = true;
            }
        });
    }

    private View currentView() {
        return switch (state.currentScreen) {
            case WELCOME        -> welcomeView;
            case PROJECT_SELECT -> projectSelectView;
            case TARGET_SELECT  -> targetSelectView;
            case SCANNING       -> scanProgressView;
            case REVIEW         -> reviewView;
        };
    }
}
