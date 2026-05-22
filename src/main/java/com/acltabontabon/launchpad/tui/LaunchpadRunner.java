package com.acltabontabon.launchpad.tui;

import com.acltabontabon.launchpad.ai.ContextGeneratorService;
import com.acltabontabon.launchpad.ai.OllamaHealthChecker;
import com.acltabontabon.launchpad.ai.OllamaStatus;
import com.acltabontabon.launchpad.scanner.ProjectScanner;
import com.acltabontabon.launchpad.standards.RemoteStandardsChecker;
import com.acltabontabon.launchpad.standards.RemoteStandardsStatus;
import com.acltabontabon.launchpad.template.ContextTemplateEngine;
import com.acltabontabon.launchpad.tui.view.ProjectSelectView;
import com.acltabontabon.launchpad.tui.view.ReviewView;
import com.acltabontabon.launchpad.tui.view.ScanProgressView;
import com.acltabontabon.launchpad.tui.view.SettingsView;
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
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Component
public class LaunchpadRunner implements ApplicationRunner {

    private static final String BRAND = "◆ Launchpad";
    private static final String USER_HOME = System.getProperty("user.home");

    private final AppState state = new AppState();

    private final WelcomeView welcomeView;
    private final ProjectSelectView projectSelectView;
    private final TargetSelectView targetSelectView;
    private final ScanProgressView scanProgressView;
    private final ReviewView reviewView;
    private final SettingsView settingsView;

    private final ProjectScanner scanner;
    private final ContextGeneratorService generatorService;
    private final OllamaHealthChecker healthChecker;
    private final RemoteStandardsChecker remoteStandardsChecker;
    private final ContextTemplateEngine templateEngine;

    private boolean scanStarted = false;

    public LaunchpadRunner(
        WelcomeView welcomeView,
        ProjectSelectView projectSelectView,
        TargetSelectView targetSelectView,
        ScanProgressView scanProgressView,
        ReviewView reviewView,
        SettingsView settingsView,
        ProjectScanner scanner,
        ContextGeneratorService generatorService,
        OllamaHealthChecker healthChecker,
        RemoteStandardsChecker remoteStandardsChecker,
        ContextTemplateEngine templateEngine
    ) {
        this.welcomeView = welcomeView;
        this.projectSelectView = projectSelectView;
        this.targetSelectView = targetSelectView;
        this.scanProgressView = scanProgressView;
        this.reviewView = reviewView;
        this.settingsView = settingsView;
        this.scanner = scanner;
        this.generatorService = generatorService;
        this.healthChecker = healthChecker;
        this.remoteStandardsChecker = remoteStandardsChecker;
        this.templateEngine = templateEngine;
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
        // Tick events drive animation - always redraw so spinners, progress bars,
        // and background-thread state changes (scan/AI) become visible.
        if (event instanceof TickEvent) {
            return true;
        }

        // Global quit - q from any screen except text input (PROJECT_SELECT, SETTINGS, WELCOME).
        // WELCOME accepts text now (slash-command palette) - quitting from there goes through /quit.
        if (event instanceof KeyEvent key
                && key.isChar('q')
                && state.currentScreen != AppState.Screen.PROJECT_SELECT
                && state.currentScreen != AppState.Screen.SETTINGS
                && state.currentScreen != AppState.Screen.WELCOME) {
            runner.quit();
            return true;
        }
        boolean handled = currentView().handleEvent(event, runner, state);
        // Any input event should refresh the screen (e.g. typing in path input).
        return handled || event instanceof KeyEvent;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void renderFrame(Frame frame) {
        triggerHealthCheckIfNeeded();
        triggerRemoteStandardsCheckIfNeeded();
        triggerScanIfNeeded();

        var area = frame.area();
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(1),   // header
                Constraint.min(0)       // content
            )
            .split(area);

        renderHeader(frame, rows.get(0));
        currentView().render(frame, rows.get(1), state);
    }

    private void renderHeader(Frame frame, Rect area) {
        var spans = new java.util.ArrayList<Span>();
        spans.add(Span.styled(" " + BRAND, Style.create().fg(Color.CYAN).bold()));

        var screen = state.currentScreen;
        var dim = Style.create().fg(Color.DARK_GRAY);

        if (screen == AppState.Screen.WELCOME) {
            spans.add(Span.styled("  ·  ", dim));
            spans.add(Span.styled("AI Context Generator", dim));
        } else if (screen == AppState.Screen.SETTINGS) {
            spans.add(Span.styled("  ·  ", dim));
            spans.add(Span.styled("Settings", Style.create().fg(Color.YELLOW).bold()));
        } else {
            if (!state.projectPath.isEmpty()) {
                spans.add(Span.styled("  ·  ", dim));
                spans.add(Span.styled(shortenPath(state.projectPath), Style.create().fg(Color.WHITE)));
            }
            if (state.launchpadAware) {
                spans.add(Span.styled("  ", dim));
                spans.add(Span.styled("✨ launchpad-aware", Style.create().fg(Color.YELLOW).bold()));
            }
            if (screen != AppState.Screen.PROJECT_SELECT) {
                spans.add(Span.styled("  ·  ", dim));
                spans.add(Span.styled("→ " + state.selectedTarget.displayName,
                    Style.create().fg(Color.GREEN)));
            }
        }

        var header = Paragraph.builder()
            .text(Text.from(Line.from(spans.toArray(new Span[0]))))
            .build();
        frame.renderWidget(header, area);
    }

    private static String shortenPath(String path) {
        var display = path.startsWith(USER_HOME) ? "~" + path.substring(USER_HOME.length()) : path;
        if (display.length() <= 40) return display;
        // Keep the last segment + ellipsis prefix for long paths.
        var slash = display.lastIndexOf('/');
        var tail = slash >= 0 ? display.substring(slash) : display;
        return "…" + tail;
    }

    // ── Background health check ────────────────────────────────────────────────

    private void triggerHealthCheckIfNeeded() {
        if (!state.healthCheckRequested) return;
        state.healthCheckRequested = false;
        state.ollamaStatus.set(OllamaStatus.checking());

        CompletableFuture.runAsync(() -> state.ollamaStatus.set(healthChecker.check()));
    }

    private void triggerRemoteStandardsCheckIfNeeded() {
        if (!state.remoteStandardsCheckRequested) return;
        state.remoteStandardsCheckRequested = false;
        state.remoteStandardsStatus.set(RemoteStandardsStatus.checking());

        CompletableFuture.runAsync(() ->
            state.remoteStandardsStatus.set(remoteStandardsChecker.check()));
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
                // Phase 1 - file scan  (eases 5 -> 25 as scanner emits progress)
                beginPhase(AppState.Phase.SCAN_FILES, 5, "Scanning project files...");
                var ctx = scanner.scan(state.projectPath, msg -> {
                    state.scanMessage.set(msg);
                    int n = state.streamedChunks.incrementAndGet();
                    int p = 5 + (int) (20 * (1 - Math.exp(-n / 30.0)));
                    state.scanProgress.set(Math.min(25, p));
                });

                // Phase 2 - AI: project summary  (eases 30 -> 55 as tokens stream)
                beginPhase(AppState.Phase.GENERATE_SUMMARY, 30, "Generating project summary with local AI...");
                var summary = generatorService.generateProjectSummary(ctx,
                    chunk -> onAiChunk(chunk, 30, 55));

                // Phase 3 - AI: target-specific content  (eases 60 -> 85)
                beginPhase(AppState.Phase.GENERATE_TARGET, 60,
                    "Generating " + state.selectedTarget.displayName + " content...");
                var targetContent = generatorService.generateTargetSpecificContent(ctx, state.selectedTarget,
                    chunk -> onAiChunk(chunk, 60, 85));

                // Phase 4 - assemble files
                beginPhase(AppState.Phase.ASSEMBLE, 90, "Assembling output files...");
                var files = templateEngine.buildFiles(ctx, state.selectedTarget, summary, targetContent);
                state.generatedFiles = files;

                // Done
                beginPhase(AppState.Phase.DONE, 100, "Done! " + files.size() + " files generated.");
                state.scanComplete = true;

            } catch (Exception e) {
                state.scanError = true;
                state.scanMessage.set("Error: " + e.getMessage());
                state.scanComplete = true;
            }
        });
    }

    private void beginPhase(AppState.Phase phase, int baseProgress, String message) {
        state.currentPhase.set(phase);
        state.scanProgress.set(baseProgress);
        state.scanMessage.set(message);
        state.streamedChunks.set(0);
        state.streamTail.set("");
        state.phaseStartedAtMs.set(System.currentTimeMillis());
    }

    private void onAiChunk(String chunk, int from, int to) {
        int n = state.streamedChunks.incrementAndGet();
        int range = to - from;
        // diminishing returns: approaches `to` asymptotically so the bar always advances
        int p = from + (int) (range * (1 - Math.exp(-n / 80.0)));
        state.scanProgress.set(Math.min(to, p));
        state.streamTail.set(appendTail(state.streamTail.get(), chunk, 120));
    }

    private static String appendTail(String prev, String chunk, int max) {
        var combined = (prev + chunk).replaceAll("\\s+", " ");
        return combined.length() <= max ? combined : combined.substring(combined.length() - max);
    }

    private View currentView() {
        return switch (state.currentScreen) {
            case WELCOME        -> welcomeView;
            case PROJECT_SELECT -> projectSelectView;
            case TARGET_SELECT  -> targetSelectView;
            case SCANNING       -> scanProgressView;
            case REVIEW         -> reviewView;
            case SETTINGS       -> settingsView;
        };
    }
}
