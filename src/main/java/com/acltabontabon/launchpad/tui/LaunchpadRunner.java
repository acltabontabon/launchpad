package com.acltabontabon.launchpad.tui;

import com.acltabontabon.launchpad.ai.ContextGeneratorService;
import com.acltabontabon.launchpad.ai.LlmProviderStatus;
import com.acltabontabon.launchpad.ai.ProviderHealthChecker;
import com.acltabontabon.launchpad.audit.AuditService;
import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.springboot.scanner.ProjectScanner;
import com.acltabontabon.launchpad.scanner.ScanStore;
import com.acltabontabon.launchpad.model.ProjectContextAssembler;
import com.acltabontabon.launchpad.model.VirtualProjectContextStore;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.RemoteStandardsChecker;
import com.acltabontabon.launchpad.standards.RemoteStandardsStatus;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import com.acltabontabon.launchpad.task.TaskAdvisorService;
import com.acltabontabon.launchpad.template.ContextTemplateEngine;
import com.acltabontabon.launchpad.template.GeneratedFile;
import com.acltabontabon.launchpad.template.LegacyPrimaryFileDetector;
import com.acltabontabon.launchpad.tui.components.Footer;
import com.acltabontabon.launchpad.tui.components.Header;
import com.acltabontabon.launchpad.tui.view.ProjectSelectView;
import com.acltabontabon.launchpad.tui.view.ProjectionSelectView;
import com.acltabontabon.launchpad.tui.view.ProjectsView;
import com.acltabontabon.launchpad.tui.view.ReviewView;
import com.acltabontabon.launchpad.tui.view.ScanProgressView;
import com.acltabontabon.launchpad.tui.view.SettingsView;
import com.acltabontabon.launchpad.tui.view.TaskInputView;
import com.acltabontabon.launchpad.tui.view.TaskInterviewView;
import com.acltabontabon.launchpad.tui.view.TaskResultView;
import com.acltabontabon.launchpad.tui.view.View;
import com.acltabontabon.launchpad.tui.view.WelcomeView;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The interactive TUI application runner.
 * <p>
 * This bean is declared unconditionally so that GraalVM AOT keeps it in every
 * native image (Spring AOT evaluates {@code @ConditionalOnProperty} at build
 * time, which prunes whichever side does not match the AOT-time property and
 * makes the binary one-mode-only). The mode check happens at runtime in
 * {@link #run(ApplicationArguments)} instead: in MCP mode we return
 * immediately so the TUI never tries to grab the terminal.
 */
@Component
public class LaunchpadRunner implements ApplicationRunner {

    private final AppState state = new AppState();

    private final WelcomeView welcomeView;
    private final ProjectSelectView projectSelectView;
    private final ProjectionSelectView projectionSelectView;
    private final ScanProgressView scanProgressView;
    private final ReviewView reviewView;
    private final SettingsView settingsView;
    private final ProjectsView projectsView;
    private final TaskInputView taskInputView;
    private final TaskInterviewView taskInterviewView;
    private final TaskResultView taskResultView;

    private final ProjectScanner scanner;
    private final ScanStore scanStore;
    private final ProjectRegistry projectRegistry;
    private final AuditService auditService;
    private final ContextGeneratorService generatorService;
    private final ProviderHealthChecker healthChecker;
    private final RemoteStandardsChecker remoteStandardsChecker;
    private final ContextTemplateEngine templateEngine;
    private final TaskAdvisorService taskAdvisor;
    private final StandardsLoader standardsLoader;
    private final LaunchpadSettings settings;
    private final ProjectContextAssembler projectContextAssembler;
    private final VirtualProjectContextStore virtualProjectContextStore;

    // Single pool for all background scan / task futures. Using a cached pool
    // (unbounded, but tasks are short-lived and serialised in practice) so each
    // submit returns a real Future we can cancel on ESC. Daemon threads so a
    // blocked HTTP call (e.g. Ollama) cannot keep the JVM alive after quit.
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "launchpad-background");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdownExecutor() {
        backgroundExecutor.shutdownNow();
    }

    /**
     * Signals every in-flight background task to abort. Sets
     * {@link AppState#cancelRequested} so cooperative cancellation points
     * (scanner phase boundaries, AI stream callbacks) exit cleanly, and calls
     * {@code cancel(true)} on each tracked future so blocking IO (HTTP to the
     * AI provider) is interrupted promptly. Invoked just before
     * {@link TuiRunner#quit()} on the second Ctrl-C so the JVM does not have
     * to rely on {@code shutdownNow} ripping threads out from under
     * partially-written state.
     */
    private void cancelAllInFlightWork() {
        state.cancelRequested = true;
        var scan = state.currentScanFuture;
        if (scan != null) scan.cancel(true);
        var question = state.currentTaskQuestionFuture;
        if (question != null) question.cancel(true);
        var finalize = state.currentTaskFinalizeFuture;
        if (finalize != null) finalize.cancel(true);
    }

    // Millisecond precision so two tasks created within the same second still
    // produce distinct filenames - per-second resolution caused later writes to
    // overwrite earlier ones when a user submitted twice quickly.
    private static final DateTimeFormatter TASK_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    // Cap on interview rounds. Real interviews converge in 3-5 rounds; 8 leaves
    // headroom for stack-specific paths while still cutting off runaway models.
    private static final int TASK_RUNAWAY_CAP = 8;
    // Maximum critic-driven follow-ups per task. The readiness critic runs only
    // when the interview model emits __DONE__; if it disagrees we inject ONE
    // follow-up question and let the interview continue. Bounded so the critic
    // cannot reject __DONE__ forever - in practice a model that disagrees twice
    // is unlikely to surface a useful third question.
    private static final int CRITIC_CAP = 2;
    // Critic only activates once the interview has produced a baseline of turns.
    // Below this, the existing pickNextUncoveredMustRule override (for the first
    // 3 rounds when [must] rules are uncovered) is the right safety net, not a
    // free-form critic call.
    private static final int MIN_ROUNDS_BEFORE_CRITIC = 2;

    public LaunchpadRunner(
        WelcomeView welcomeView,
        ProjectSelectView projectSelectView,
        ProjectionSelectView projectionSelectView,
        ScanProgressView scanProgressView,
        ReviewView reviewView,
        SettingsView settingsView,
        ProjectsView projectsView,
        TaskInputView taskInputView,
        TaskInterviewView taskInterviewView,
        TaskResultView taskResultView,
        ProjectScanner scanner,
        ScanStore scanStore,
        ProjectRegistry projectRegistry,
        AuditService auditService,
        ContextGeneratorService generatorService,
        ProviderHealthChecker healthChecker,
        RemoteStandardsChecker remoteStandardsChecker,
        ContextTemplateEngine templateEngine,
        TaskAdvisorService taskAdvisor,
        StandardsLoader standardsLoader,
        LaunchpadSettings settings,
        ProjectContextAssembler projectContextAssembler,
        VirtualProjectContextStore virtualProjectContextStore
    ) {
        this.welcomeView = welcomeView;
        this.projectSelectView = projectSelectView;
        this.projectionSelectView = projectionSelectView;
        this.scanProgressView = scanProgressView;
        this.reviewView = reviewView;
        this.settingsView = settingsView;
        this.projectsView = projectsView;
        this.taskInputView = taskInputView;
        this.taskInterviewView = taskInterviewView;
        this.taskResultView = taskResultView;
        this.scanner = scanner;
        this.scanStore = scanStore;
        this.projectRegistry = projectRegistry;
        this.auditService = auditService;
        this.generatorService = generatorService;
        this.healthChecker = healthChecker;
        this.remoteStandardsChecker = remoteStandardsChecker;
        this.templateEngine = templateEngine;
        this.taskAdvisor = taskAdvisor;
        this.standardsLoader = standardsLoader;
        this.settings = settings;
        this.projectContextAssembler = projectContextAssembler;
        this.virtualProjectContextStore = virtualProjectContextStore;
        this.state.activeModel = settings.snapshot().model();
    }

    @org.springframework.context.event.EventListener
    void onLlmProviderSettingsChanged(LaunchpadSettings.LlmProviderSettingsChanged event) {
        state.activeModel = event.snapshot().model();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // In MCP mode the JSON-RPC server owns stdin/stdout and the process has
        // no usable TTY - starting the TUI here would crash on "Failed to get
        // terminal attributes". `launchpad.mode` is set by LaunchpadApplication
        // before SpringApplication.run, so it is reliable at this point.
        if ("mcp".equals(System.getProperty("launchpad.mode"))) {
            return;
        }
        var config = TuiConfig.builder()
            .tickRate(Duration.ofMillis(80))  // ~12fps - enough for smooth spinner
            .mouseCapture(true)               // so scroll-wheel events reach the views as MouseEvent, not as faked arrow keys
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

        // Global Ctrl-C: prompt for confirmation, then quit on a second press.
        // Mirrors Claude CLI's "press Ctrl-C again to exit" guard - prevents
        // accidental exits from a fat-fingered terminal shortcut and works
        // uniformly across text-input screens where 'q' is a content character.
        // The second press cancels any in-flight background work before
        // quitting so partial state unwinds cleanly instead of relying on
        // executor shutdownNow to interrupt blocked HTTP.
        if (event instanceof KeyEvent key && key.isCtrlC()) {
            if (state.quitConfirmPending) {
                cancelAllInFlightWork();
                runner.quit();
            } else {
                state.quitConfirmPending = true;
            }
            return true;
        }

        // Global quit - q from any screen except text-input screens.
        // WELCOME accepts q as quit when the palette is closed; once the user has
        // opened the palette by typing '/', q is a filter character (e.g. /quit).
        // TASK_INPUT / TASK_INTERVIEW accept text (description, answers); TASK_RESULT
        // uses q as "back to welcome" instead of quit so the user can start another task.
        // During an active scan, the first q shows a confirmation banner; a second q quits.
        if (event instanceof KeyEvent key
                && key.isChar('q')
                && state.currentScreen != AppState.Screen.PROJECT_SELECT
                && state.currentScreen != AppState.Screen.SETTINGS
                && state.currentScreen != AppState.Screen.TASK_INPUT
                && state.currentScreen != AppState.Screen.TASK_INTERVIEW
                && state.currentScreen != AppState.Screen.TASK_RESULT
                && !(state.currentScreen == AppState.Screen.WELCOME
                     && state.commandInput.startsWith("/"))) {
            if (state.currentScreen == AppState.Screen.SCANNING && !state.scanComplete) {
                if (state.quitConfirmPending) {
                    cancelAllInFlightWork();
                    runner.quit();
                } else {
                    state.quitConfirmPending = true;
                }
                return true;
            }
            cancelAllInFlightWork();
            runner.quit();
            return true;
        }

        // Any key press that is neither a quit attempt (Ctrl-C / eligible q)
        // nor a tick dismisses a pending quit confirmation. The warning
        // signals "you almost exited"; once the user starts doing something
        // else it should clear so it does not linger across unrelated input.
        // ESC also clears it via ScanProgressView's existing handler when on
        // the scan screen.
        if (event instanceof KeyEvent dismissKey
                && state.quitConfirmPending
                && !dismissKey.isCtrlC()) {
            state.quitConfirmPending = false;
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
        triggerTaskQuestionIfNeeded();
        triggerTaskFinalizeIfNeeded();

        var area = frame.area();
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(1),   // header
                Constraint.min(0),      // content
                Constraint.length(1)    // footer
            )
            .split(area);

        Header.render(frame, rows.get(0), state);
        var view = currentView();
        view.render(frame, rows.get(1), state);
        Footer.render(frame, rows.get(2), state, view.footerHints(state));
    }

    // ── Background health check ────────────────────────────────────────────────

    private void triggerHealthCheckIfNeeded() {
        if (!state.healthCheckRequested) return;
        state.healthCheckRequested = false;
        state.ollamaStatus.set(LlmProviderStatus.checking());

        backgroundExecutor.submit(() -> state.ollamaStatus.set(healthChecker.check()));
    }

    private void triggerRemoteStandardsCheckIfNeeded() {
        if (!state.remoteStandardsCheckRequested) return;
        state.remoteStandardsCheckRequested = false;
        state.remoteStandardsStatus.set(RemoteStandardsStatus.checking());

        backgroundExecutor.submit(() ->
            state.remoteStandardsStatus.set(remoteStandardsChecker.check()));
    }

    // ── Background scan pipeline ───────────────────────────────────────────────

    /**
     * Triggered once when the user enters the SCANNING screen.
     * Runs the full pipeline in a background thread, updating AppState for the TUI to read.
     * When {@code state.taskFlow} is true, only the scan phase runs and the next screen
     * is TASK_INPUT - the /new-task flow doesn't generate context files.
     */
    private void triggerScanIfNeeded() {
        if (state.currentScreen != AppState.Screen.SCANNING || state.scanStarted) return;
        state.scanStarted = true;

        var future = backgroundExecutor.submit(() -> {
            try {
                // Phase 1 - file scan  (eases 5 -> 25 as scanner emits progress)
                beginPhase(AppState.Phase.SCAN_FILES, 5, "Scanning project files...");
                state.pushActivity("scan", "walking project tree at " + state.projectPath);
                if (checkCancelled()) return;
                var ctx = scanner.scan(state.projectPath, msg -> {
                    if (state.cancelRequested) throw new CancelledException();
                    state.scanMessage.set(msg);
                    state.currentItem.set(msg);
                    int n = state.streamedChunks.incrementAndGet();
                    int p = 5 + (int) (20 * (1 - Math.exp(-n / 30.0)));
                    state.scanProgress.set(Math.min(25, p));
                });

                state.statsFilesScanned.set(ctx.sourceFiles() == null ? 0 : ctx.sourceFiles().size());
                state.statsPackages.set(ctx.packageSummaries() == null ? 0 : ctx.packageSummaries().size());
                state.statsDependencies.set(ctx.dependencies() == null ? 0 : ctx.dependencies().size());
                state.currentItem.set("");
                var stack = ctx.stack();
                var stackLabel = stack == null ? "unknown stack"
                    : (stack.framework() == null ? stack.language() : stack.framework())
                        + (stack.buildTool() == null ? "" : "  " + "·" + "  " + stack.buildTool());
                state.pushActivity("scan", "scanned " + state.statsFilesScanned.get()
                    + " files  " + "·" + "  " + state.statsPackages.get() + " packages  "
                    + "·" + "  " + state.statsDependencies.get() + " deps", "success");
                state.pushActivity("scan", "detected " + stackLabel);

                if (checkCancelled()) return;
                var projectRootForAudit = Path.of(state.projectPath).toAbsolutePath();
                scanStore.save(projectRootForAudit, ctx);

                // Assemble the virtualized project model. This is the
                // synthesized understanding the rest of the pipeline projects
                // from (AGENTS.md + .ai/*) and that out-of-process MCP
                // consumers read. Assembly is deterministic; the persist is
                // best-effort so a write failure cannot block the run.
                var model = projectContextAssembler.assemble(
                    ctx, "", java.time.Instant.now().toString());
                try {
                    virtualProjectContextStore.save(projectRootForAudit, model);
                } catch (Exception modelError) {
                    org.slf4j.LoggerFactory.getLogger(LaunchpadRunner.class)
                        .warn("Failed to persist project model for {}", state.projectPath, modelError);
                }

                registerProjectQuietly(projectRootForAudit, ctx.stack());
                runAuditPhase(ctx, projectRootForAudit);

                if (checkCancelled()) return;

                if (state.taskFlow) {
                    // /new-task: scan-only. Stash the context and jump straight to the
                    // task input screen. No LLM summary / target / assemble phases here -
                    // those are for /init only.
                    state.taskProjectContext = ctx;
                    beginPhase(AppState.Phase.DONE, 100, "Project scanned. Describe your task next.");
                    state.scanComplete = true;
                    state.currentScreen = AppState.Screen.TASK_INPUT;
                    return;
                }

                // Phase 2 - assemble files
                beginPhase(AppState.Phase.ASSEMBLE, 30, "Assembling output files...");
                state.pushActivity("assemble", "rendering vendor-neutral output set");
                if (checkCancelled()) return;
                var files = templateEngine.buildFiles(ctx, model);
                state.generatedFiles = files;
                var projectRoot = java.nio.file.Path.of(state.projectPath).toAbsolutePath();
                var plans = new java.util.ArrayList<com.acltabontabon.launchpad.template.FilePlan>();
                for (var f : files) plans.add(com.acltabontabon.launchpad.template.FilePlan.compute(f, projectRoot));
                state.filePlans = plans;
                detectLegacyPrimaryFiles(projectRoot, files);
                state.pushActivity("assemble", "prepared " + files.size() + " files for review", "success");

                // Done
                beginPhase(AppState.Phase.DONE, 100, "Done! " + files.size() + " files generated.");
                state.pushActivity("done", "press enter to review changes", "success");
                state.scanComplete = true;

            } catch (CancelledException | java.util.concurrent.CancellationException ignored) {
                // User-requested cancel - navigate back to Welcome cleanly.
                state.resetScanFlow();
                state.currentScreen = AppState.Screen.WELCOME;
            } catch (Exception e) {
                // Unwrap CancelledException from IOException (Files.walkFileTree wraps it).
                if (isCancelCause(e)) {
                    state.resetScanFlow();
                    state.currentScreen = AppState.Screen.WELCOME;
                    return;
                }
                state.scanError = true;
                state.scanMessage.set(buildLlmErrorMessage("generation", e));
                state.scanComplete = true;
            }
        });
        state.currentScanFuture = future;
    }

    /**
     * If the project already contains legacy primary-instruction files
     * (CLAUDE.md, .cursorrules) that Launchpad no longer regenerates, surface
     * a warning so the user knows to delete or migrate them. The note lands
     * in the WARN log and in AppState.generationWarnings so the Review screen
     * banner picks it up. The generated AGENTS.md body itself stays clean.
     */
    private void detectLegacyPrimaryFiles(Path projectRoot,
                                          List<GeneratedFile> files) {
        LegacyPrimaryFileDetector.detect(projectRoot, files)
            .ifPresent(msg -> {
                org.slf4j.LoggerFactory.getLogger(LaunchpadRunner.class).warn(msg);
                var merged = new java.util.ArrayList<>(state.generationWarnings);
                merged.add(msg);
                state.generationWarnings = merged;
            });
    }

    // ── /new-task background dispatch ──────────────────────────────────────────

    /**
     * Fires when the interview screen needs a new question: no thinking in flight,
     * no current question on screen, and the user hasn't asked to finalize. Caps
     * iterations at TASK_RUNAWAY_CAP as a safety net against a misbehaving model -
     * the primary stop signal is the model returning {@link TaskAdvisorService#DONE_TOKEN}.
     */
    private void triggerTaskQuestionIfNeeded() {
        if (state.currentScreen != AppState.Screen.TASK_INTERVIEW) return;
        if (state.taskThinking || state.taskReadyToFinalize) return;
        if (!state.taskCurrentQuestion.get().isEmpty()) return;
        if (state.taskProjectContext == null) return;

        if (state.taskRound >= TASK_RUNAWAY_CAP) {
            state.taskReadyToFinalize = true;
            state.currentScreen = AppState.Screen.TASK_RESULT;
            return;
        }

        state.taskThinking = true;
        state.taskError = false;
        state.taskOpStartedAtMs = System.currentTimeMillis();
        state.taskStatus.set("asking the local model for the next clarifying question...");

        var taskQFuture = backgroundExecutor.submit(() -> {
            try {
                var projectRoot = Path.of(state.projectPath).toAbsolutePath();
                ensureStandardsCached(projectRoot);
                if (state.cancelRequested) return;
                var rules = state.taskRelevantRules;
                var skills = state.taskRelevantSkills;
                var checklists = state.taskRelevantChecklists;

                // If the project has no relevant standards at all, there's nothing
                // worth interviewing about - skip straight to finalize. The synthesised
                // prompt will still benefit from the codebase scan in the Constraints
                // section; the interview is purely a standards-driven phase.
                boolean noStandards = rules.isEmpty() && skills.isEmpty() && checklists.isEmpty();
                if (noStandards && state.taskRound == 0) {
                    state.taskStatus.set("no engineering standards found - skipping interview");
                    state.taskReadyToFinalize = true;
                    state.currentScreen = AppState.Screen.TASK_RESULT;
                    return;
                }

                var response = taskAdvisor.askNextQuestion(
                    state.taskProjectContext.stack(),
                    state.taskDescription,
                    state.taskTurns.get(),
                    rules,
                    skills,
                    checklists
                );
                if (state.cancelRequested) return;
                if (response.equals(TaskAdvisorService.DONE_TOKEN)
                        || response.contains(TaskAdvisorService.DONE_TOKEN)) {
                    // The interview model declared the brief done. Before we
                    // accept that and synthesise, run a second local-AI pass
                    // that looks at the transcript and asks "is this actually
                    // substantial?". If the critic finds a gap, we inject its
                    // follow-up question as the next interview turn. Bounded
                    // by CRITIC_CAP so a stubborn critic can't loop forever,
                    // and skipped below MIN_ROUNDS_BEFORE_CRITIC since the
                    // existing must-rule override handles the early-DONE case.
                    if (state.taskCritiqueCount < CRITIC_CAP
                            && state.taskTurns.get().size() >= MIN_ROUNDS_BEFORE_CRITIC) {
                        state.taskStatus.set("checking whether the local model has enough context...");
                        var verdict = taskAdvisor.critiqueReadiness(
                            state.taskProjectContext.stack(),
                            state.taskDescription,
                            state.taskTurns.get(),
                            rules, skills, checklists);
                        if (state.cancelRequested) return;
                        if (!verdict.equals(TaskAdvisorService.READY_TOKEN)) {
                            state.taskCritiqueCount++;
                            state.taskCurrentQuestion.set(verdict);
                            state.taskStatus.set("");
                            return;
                        }
                    }
                    state.taskReadyToFinalize = true;
                    state.currentScreen = AppState.Screen.TASK_RESULT;
                } else {
                    state.taskCurrentQuestion.set(response);
                }
                state.taskStatus.set("");
            } catch (Exception e) {
                if (state.cancelRequested) return;
                state.taskError = true;
                state.taskStatus.set(buildLlmErrorMessage("interview", e));
            } finally {
                state.taskThinking = false;
                state.taskOpStartedAtMs = 0L;
            }
        });
        state.currentTaskQuestionFuture = taskQFuture;
    }

    /**
     * Load + scope-filter standards once per task and stash the result on
     * AppState. Subsequent interview turns reuse the cached lists instead of
     * re-reading the YAML pack and re-filtering on every dispatch. Re-derived
     * when the cache is null - that happens on task entry, after resetTaskFlow,
     * or after resetTaskForReuse (because new task tags / opt-outs change the
     * scope-filter result).
     */
    private void ensureStandardsCached(Path projectRoot) {
        if (state.taskRelevantRules != null
                && state.taskRelevantSkills != null
                && state.taskRelevantChecklists != null) {
            return;
        }
        var allRules = standardsLoader.loadRules(projectRoot);
        var allSkills = standardsLoader.loadSkills(projectRoot);
        var allChecklists = standardsLoader.loadChecklists(projectRoot);
        var stack = state.taskProjectContext == null ? null : state.taskProjectContext.stack();
        var relevant = TaskAdvisorService.selectRelevantStandards(
            stack, state.taskDescription, state.taskTurns.get(),
            allRules, allSkills, allChecklists);
        state.taskRelevantRules = relevant.rules();
        state.taskRelevantSkills = relevant.skills();
        state.taskRelevantChecklists = relevant.checklists();
    }

    /**
     * Fires when the user lands on the result screen with no synthesised prompt yet.
     * Runs finalize() on a background thread and writes the result to
     * {@code <projectRoot>/.launchpad/tasks/<ts>-<slug>.md}.
     */
    private void triggerTaskFinalizeIfNeeded() {
        if (state.currentScreen != AppState.Screen.TASK_RESULT) return;
        if (state.taskThinking || !state.taskFinalPrompt.isEmpty()) return;
        if (state.taskProjectContext == null) return;

        state.taskThinking = true;
        state.taskError = false;
        state.taskOpStartedAtMs = System.currentTimeMillis();
        state.taskStatus.set("synthesising the prompt from the local model...");

        var taskFFuture = backgroundExecutor.submit(() -> {
            try {
                var projectRoot = Path.of(state.projectPath).toAbsolutePath();
                ensureStandardsCached(projectRoot);
                if (state.cancelRequested) return;
                var result = taskAdvisor.synthesise(
                    state.taskProjectContext,
                    state.taskDescription,
                    state.taskTurns.get(),
                    state.taskRelevantRules,
                    state.taskRelevantSkills,
                    state.taskRelevantChecklists,
                    chunk -> {
                        if (state.cancelRequested) throw new CancelledException();
                        state.taskFinalPrompt = chunk.isEmpty() ? "" : chunk;
                    }
                );
                if (state.cancelRequested) return;

                // Ground the synthesised prompt in the persisted project model:
                // append an Execution context section naming the systems the task
                // affects, the relevant workflows, and the risks to watch. This
                // is the cheap, repeatable discovery local synthesis does so the
                // cloud agent doesn't re-derive it from source. Best-effort - a
                // missing or stale model just yields no section.
                var grounded = result.markdown();
                var grounding = com.acltabontabon.launchpad.task.TaskModelGrounding.ground(
                    state.taskDescription,
                    virtualProjectContextStore.load(projectRoot).orElse(null));
                if (!grounding.markdown().isBlank()) {
                    grounded = grounded + "\n\n" + grounding.markdown();
                }
                state.taskFinalPrompt = grounded;
                state.taskWarnings = new java.util.ArrayList<>(result.warnings());

                // Save the prompt to disk + an `<!-- interview -->` appendix that
                // captures the raw task description and Q/A transcript so the
                // source of the synthesised prompt is preserved. Failures here
                // are non-fatal; the user can still copy from the TUI.
                try {
                    var taskDir = projectRoot.resolve(".launchpad").resolve("tasks");
                    Files.createDirectories(taskDir);
                    var ts = LocalDateTime.now().format(TASK_TS_FMT);
                    var slug = slugify(state.taskDescription);
                    var file = taskDir.resolve(ts + "-" + slug + ".md");
                    var fullContent = grounded + "\n\n" + renderInterviewAppendix(
                        state.taskDescription, state.taskTurns.get(), ts);
                    Files.writeString(file, fullContent);
                    state.taskSavedPath = file.toString();
                    state.taskStatus.set("");
                } catch (Exception writeFail) {
                    state.taskStatus.set("displayed but could not save: " + writeFail.getMessage());
                }
            } catch (CancelledException | java.util.concurrent.CancellationException ignored) {
                // User-requested cancel - the view already navigated to Welcome.
            } catch (Exception e) {
                if (state.cancelRequested) return;
                state.taskError = true;
                state.taskStatus.set(buildLlmErrorMessage("synthesise", e));
                // Clear any partial stream so the user sees the error instead of a half-prompt.
                state.taskFinalPrompt = "";
            } finally {
                state.taskThinking = false;
                state.taskOpStartedAtMs = 0L;
            }
        });
        state.currentTaskFinalizeFuture = taskFFuture;
    }

    /**
     * Heuristic error formatter. Local models behind Ollama fail in two distinctive
     * ways worth calling out to the user: context-window truncation (the prompt was
     * bigger than the model's loaded `num_ctx`, often manifests as a 500), and the
     * daemon being unreachable. Anything else passes through as-is.
     */
    private static String buildLlmErrorMessage(String phase, Throwable e) {
        var msg = e.getMessage() == null ? e.toString() : e.getMessage();
        var lower = msg.toLowerCase();
        var base = phase + " failed: " + msg;
        if (isTimeout(e)) {
            return phase + " timed out: the local model stalled. Check that Ollama is "
                + "responsive, or raise `launchpad.ai.read-timeout` in settings.";
        }
        if (lower.contains("500") || lower.contains("truncat") || lower.contains("context")) {
            return base + "  -  hint: the prompt may have exceeded Ollama's loaded context window "
                + "(check the Ollama log for 'truncating input prompt'). Try a model with a larger "
                + "num_ctx, or simplify the task.";
        }
        if (lower.contains("connect") || lower.contains("refused") || lower.contains("unreachable")) {
            return base + "  -  hint: Ollama may not be running. Check the Welcome screen's status badge.";
        }
        return base;
    }

    /** True if any cause in the chain is a {@link java.util.concurrent.TimeoutException}. */
    private static boolean isTimeout(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException) return true;
            if (t == t.getCause()) return false;
        }
        return false;
    }

    /**
     * Builds the HTML-commented interview appendix appended to every saved task
     * file. Captures the raw task description + Q/A transcript so the source
     * of the synthesised prompt is traceable later. Markdown comment delimiters
     * (`<!-- ... -->`) keep this invisible when the file is rendered, while
     * still being plain text the user can read in any editor.
     */
    private static String renderInterviewAppendix(String taskDescription,
            java.util.List<com.acltabontabon.launchpad.task.TaskTurn> turns, String timestamp) {
        var sb = new StringBuilder();
        sb.append("<!-- launchpad-interview\n");
        sb.append("generated: ").append(timestamp).append("\n\n");
        sb.append("Original task (as the user typed it):\n");
        sb.append("  ").append(taskDescription.replace("\n", "\n  ")).append("\n\n");
        if (turns == null || turns.isEmpty()) {
            sb.append("Interview: (none - no questions were asked)\n");
        } else {
            sb.append("Interview Q&A:\n");
            int i = 1;
            for (var turn : turns) {
                sb.append("  Q").append(i).append(": ").append(turn.question()).append("\n");
                sb.append("  A").append(i).append(": ").append(turn.answer()).append("\n\n");
                i++;
            }
        }
        sb.append("-->\n");
        return sb.toString();
    }

    private static String slugify(String text) {
        if (text == null) return "task";
        var lower = text.toLowerCase().strip();
        var trimmed = lower.length() > 60 ? lower.substring(0, 60) : lower;
        var s = trimmed.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "task" : s;
    }

    /**
     * Enroll the project in the user-global registry so MCP clients can address
     * it by short name across sessions. Registry write failures are not fatal -
     * the scan / audit / generation flow continues regardless. The message is
     * swallowed quietly because the user did not explicitly ask to register;
     * the registry is a side-effect of normal use.
     */
    private void registerProjectQuietly(Path projectRoot, StackProfile stack) {
        try {
            projectRegistry.register(projectRoot, describeStack(stack));
        } catch (RuntimeException ignored) {
            // Best-effort - never block the scan pipeline on a registry write.
        }
    }

    private static String describeStack(StackProfile stack) {
        if (stack == null) return null;
        var language = stack.language();
        var framework = stack.framework();
        if (language != null && framework != null && !framework.isBlank()) {
            return language + " / " + framework;
        }
        return language;
    }

    /**
     * Runs the audit between scan and generation. Silently no-ops when no rules
     * in the active standards pack carry a {@code check} block - keeps existing
     * setups (no audit defined) unchanged. The two output files
     * ({@code .launchpad/audit.md}, {@code .launchpad/audit.sarif.json}) are
     * written for downstream tooling (TUI, MCP server, IDE SARIF viewers).
     */
    private void runAuditPhase(com.acltabontabon.launchpad.scanner.ProjectContext ctx, Path projectRoot) {
        try {
            beginPhase(AppState.Phase.AUDIT_STANDARDS, 26, "Auditing project against standards...");
            var lastFindings = new java.util.concurrent.atomic.AtomicInteger(0);
            var result = auditService.run(ctx, projectRoot, progress -> {
                state.scanMessage.set("Auditing: " + progress.ruleId()
                    + " (" + progress.index() + "/" + progress.total() + ")");
                state.currentItem.set("rule " + progress.index() + " of " + progress.total()
                    + "  ·  " + progress.ruleId());
                state.statsRulesTotal.set(progress.total());
                // Smooth the gauge from 26 -> 60 across the rules.
                int span = 60 - 26;
                int p = 26 + (int) (span * (progress.index() / (double) Math.max(1, progress.total())));
                state.scanProgress.set(Math.min(60, p));
                state.pushActivity("audit", "checking " + progress.ruleId()
                    + "  (" + progress.index() + "/" + progress.total() + ")");
                lastFindings.set(state.auditFindingsCount.get());
            });
            state.auditRulesEvaluated.set(result.rulesAudited());
            state.auditFindingsCount.set(result.findings().size());
            var counts = result.countsBySeverity();
            state.auditMustCount.set(counts.getOrDefault("must", 0L).intValue()
                + counts.getOrDefault("never", 0L).intValue());
            state.auditShouldCount.set(counts.getOrDefault("should", 0L).intValue());
            state.auditMarkdownPath = result.markdownPath() == null ? null : result.markdownPath().toString();
            state.auditSarifPath = result.sarifPath() == null ? null : result.sarifPath().toString();
            state.currentItem.set("");
            int findings = result.findings().size();
            if (findings == 0 && result.rulesAudited() > 0) {
                state.pushActivity("audit",
                    "all " + result.rulesAudited() + " rules clean", "success");
            } else if (findings > 0) {
                state.pushActivity("audit",
                    findings + " findings  ·  "
                        + state.auditMustCount.get() + " must, "
                        + state.auditShouldCount.get() + " should",
                    "finding");
            }
        } catch (RuntimeException e) {
            state.scanMessage.set("Audit skipped: " + e.getMessage());
            state.pushActivity("audit", "skipped: " + e.getMessage(), "warn");
        }
    }

    /**
     * Checks the cancel flag. When set, resets scan state and routes back to Welcome.
     * Returns true if the caller should stop processing immediately.
     */
    private boolean checkCancelled() {
        if (!state.cancelRequested) return false;
        state.resetScanFlow();
        state.currentScreen = AppState.Screen.WELCOME;
        return true;
    }

    /**
     * Returns true when any cause in the exception chain is a {@link CancelledException}.
     * Used to detect cancellation wrapped by {@code Files.walkFileTree}'s IOException.
     */
    private static boolean isCancelCause(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            if (t instanceof CancelledException) return true;
            if (t == t.getCause()) return false;
        }
        return false;
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
            case WELCOME           -> welcomeView;
            case PROJECT_SELECT    -> projectSelectView;
            case PROJECTION_SELECT -> projectionSelectView;
            case SCANNING          -> scanProgressView;
            case REVIEW         -> reviewView;
            case TASK_INPUT     -> taskInputView;
            case TASK_INTERVIEW -> taskInterviewView;
            case TASK_RESULT    -> taskResultView;
            case SETTINGS       -> settingsView;
            case PROJECTS       -> projectsView;
        };
    }
}
