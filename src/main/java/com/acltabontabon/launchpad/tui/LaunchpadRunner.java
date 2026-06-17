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
import com.acltabontabon.launchpad.model.graph.ProjectModelAssembler;
import com.acltabontabon.launchpad.model.graph.ProjectModelStore;
import com.acltabontabon.launchpad.standards.index.StandardsIndexAssembler;
import com.acltabontabon.launchpad.standards.index.StandardsIndexStore;
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
    private final ProjectModelAssembler projectModelAssembler;
    private final ProjectModelStore projectModelStore;
    private final StandardsIndexAssembler standardsIndexAssembler;
    private final StandardsIndexStore standardsIndexStore;

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
        var scan = state.scan.future;
        if (scan != null) scan.cancel(true);
        var question = state.task.questionFuture;
        if (question != null) question.cancel(true);
        var finalize = state.task.finalizeFuture;
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
        VirtualProjectContextStore virtualProjectContextStore,
        ProjectModelAssembler projectModelAssembler,
        ProjectModelStore projectModelStore,
        StandardsIndexAssembler standardsIndexAssembler,
        StandardsIndexStore standardsIndexStore
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
        this.projectModelAssembler = projectModelAssembler;
        this.projectModelStore = projectModelStore;
        this.standardsIndexAssembler = standardsIndexAssembler;
        this.standardsIndexStore = standardsIndexStore;
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
        if (event instanceof KeyEvent key
                && key.isChar('q')
                && state.nav.currentScreen != AppState.Screen.PROJECT_SELECT
                && state.nav.currentScreen != AppState.Screen.SETTINGS
                && state.nav.currentScreen != AppState.Screen.TASK_INPUT
                && state.nav.currentScreen != AppState.Screen.TASK_INTERVIEW
                && state.nav.currentScreen != AppState.Screen.TASK_RESULT
                && !(state.nav.currentScreen == AppState.Screen.WELCOME
                     && state.nav.commandInput.startsWith("/"))) {
            if (state.nav.currentScreen == AppState.Screen.SCANNING && !state.scan.complete) {
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

        // Any key press that is neither a quit attempt nor a tick dismisses a
        // pending quit confirmation.
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
     * When {@code state.task.flow} is true, only the scan phase runs and the next screen
     * is TASK_INPUT - the /new-task flow doesn't generate context files.
     */
    private void triggerScanIfNeeded() {
        if (state.nav.currentScreen != AppState.Screen.SCANNING || state.scan.started) return;
        state.scan.started = true;

        var future = backgroundExecutor.submit(() -> {
            try {
                // Eases 5 -> 25 as the scanner emits progress, so the bar
                // moves smoothly even when chunk counts are bursty.
                beginPhase(AppState.Phase.SCAN_FILES, 5, "Scanning project files...");
                state.scan.pushActivity("scan", "walking project tree at " + state.projectPath);
                if (checkCancelled()) return;
                var ctx = scanner.scan(state.projectPath, msg -> {
                    if (state.cancelRequested) throw new CancelledException();
                    state.scan.message.set(msg);
                    state.scan.currentItem.set(msg);
                    int n = state.scan.streamedChunks.incrementAndGet();
                    int p = 5 + (int) (20 * (1 - Math.exp(-n / 30.0)));
                    state.scan.progress.set(Math.min(25, p));
                });

                state.scan.statsFilesScanned.set(ctx.sourceFiles() == null ? 0 : ctx.sourceFiles().size());
                state.scan.statsPackages.set(ctx.packageSummaries() == null ? 0 : ctx.packageSummaries().size());
                state.scan.statsDependencies.set(ctx.dependencies() == null ? 0 : ctx.dependencies().size());
                state.scan.currentItem.set("");
                var stack = ctx.stack();
                var stackLabel = stack == null ? "unknown stack"
                    : (stack.framework() == null ? stack.language() : stack.framework())
                        + (stack.buildTool() == null ? "" : "  " + "·" + "  " + stack.buildTool());
                state.scan.pushActivity("scan", "scanned " + state.scan.statsFilesScanned.get()
                    + " files  " + "·" + "  " + state.scan.statsPackages.get() + " packages  "
                    + "·" + "  " + state.scan.statsDependencies.get() + " deps", "success");
                state.scan.pushActivity("scan", "detected " + stackLabel);

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

                // Deterministic, machine-readable project-model graph sidecar
                // (.launchpad/project.model.json). LLM-free; best-effort so a
                // write failure cannot block the run.
                try {
                    var projectModel = projectModelAssembler.assemble(ctx, java.time.Instant.now().toString());
                    projectModelStore.save(projectRootForAudit, projectModel);
                } catch (Exception graphError) {
                    org.slf4j.LoggerFactory.getLogger(LaunchpadRunner.class)
                        .warn("Failed to persist project-model graph for {}", state.projectPath, graphError);
                }

                // Deterministic, machine-readable standards sidecar
                // (.launchpad/standards.index.json): one record per resolved rule
                // so an agent can retrieve a single rule and audit findings can
                // resolve their ruleId against a canonical registry. Rules and
                // their source come from one resolution so they never disagree.
                // LLM-free; best-effort so a write failure cannot block the run.
                try {
                    var resolved = standardsLoader.loadResolvedRules(projectRootForAudit);
                    var standardsIndex = standardsIndexAssembler.assemble(
                        resolved.rules(), resolved.source(), java.time.Instant.now().toString());
                    standardsIndexStore.save(projectRootForAudit, standardsIndex);
                } catch (Exception indexError) {
                    org.slf4j.LoggerFactory.getLogger(LaunchpadRunner.class)
                        .warn("Failed to persist standards index for {}", state.projectPath, indexError);
                }

                registerProjectQuietly(projectRootForAudit, ctx.stack());
                runAuditPhase(ctx, projectRootForAudit);

                if (checkCancelled()) return;

                if (state.task.flow) {
                    // /new-task: scan-only. Stash the context and jump straight to the
                    // task input screen. No LLM summary / target / assemble phases here -
                    // those are for /init only.
                    state.task.projectContext = ctx;
                    beginPhase(AppState.Phase.DONE, 100, "Project scanned. Describe your task next.");
                    state.scan.complete = true;
                    state.nav.currentScreen = AppState.Screen.TASK_INPUT;
                    return;
                }

                beginPhase(AppState.Phase.ASSEMBLE, 30, "Assembling output files...");
                state.scan.pushActivity("assemble", "rendering vendor-neutral output set");
                if (checkCancelled()) return;
                var files = templateEngine.buildFiles(ctx, model);
                state.gen.files = files;
                var projectRoot = java.nio.file.Path.of(state.projectPath).toAbsolutePath();
                var plans = new java.util.ArrayList<com.acltabontabon.launchpad.template.FilePlan>();
                for (var f : files) plans.add(com.acltabontabon.launchpad.template.FilePlan.compute(f, projectRoot));
                state.gen.plans = plans;
                detectLegacyPrimaryFiles(projectRoot, files);
                state.scan.pushActivity("assemble", "prepared " + files.size() + " files for review", "success");

                // Done
                beginPhase(AppState.Phase.DONE, 100, "Done! " + files.size() + " files generated.");
                state.scan.pushActivity("done", "press enter to review changes", "success");
                state.scan.complete = true;

            } catch (CancelledException | java.util.concurrent.CancellationException ignored) {
                // User-requested cancel - navigate back to Welcome cleanly.
                state.resetScanFlow();
                state.nav.currentScreen = AppState.Screen.WELCOME;
            } catch (Exception e) {
                // Unwrap CancelledException from IOException (Files.walkFileTree wraps it).
                if (isCancelCause(e)) {
                    state.resetScanFlow();
                    state.nav.currentScreen = AppState.Screen.WELCOME;
                    return;
                }
                state.scan.error = true;
                state.scan.message.set(buildLlmErrorMessage("generation", e));
                state.scan.complete = true;
            }
        });
        state.scan.future = future;
    }

    /**
     * If the project already contains legacy primary-instruction files
     * (CLAUDE.md, .cursorrules) that Launchpad no longer regenerates, surface
     * a warning so the user knows to delete or migrate them. The note lands
     * in the WARN log and in the generation warnings so the Review screen
     * banner picks it up. The generated AGENTS.md body itself stays clean.
     */
    private void detectLegacyPrimaryFiles(Path projectRoot,
                                          List<GeneratedFile> files) {
        LegacyPrimaryFileDetector.detect(projectRoot, files)
            .ifPresent(msg -> {
                org.slf4j.LoggerFactory.getLogger(LaunchpadRunner.class).warn(msg);
                var merged = new java.util.ArrayList<>(state.gen.warnings);
                merged.add(msg);
                state.gen.warnings = merged;
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
        if (state.nav.currentScreen != AppState.Screen.TASK_INTERVIEW) return;
        if (state.task.thinking || state.task.readyToFinalize) return;
        if (!state.task.currentQuestion.get().isEmpty()) return;
        if (state.task.projectContext == null) return;

        if (state.task.round >= TASK_RUNAWAY_CAP) {
            state.task.readyToFinalize = true;
            state.nav.currentScreen = AppState.Screen.TASK_RESULT;
            return;
        }

        state.task.thinking = true;
        state.task.error = false;
        state.task.opStartedAtMs = System.currentTimeMillis();
        state.task.status.set("asking the local model for the next clarifying question...");

        var taskQFuture = backgroundExecutor.submit(() -> {
            try {
                var projectRoot = Path.of(state.projectPath).toAbsolutePath();
                ensureStandardsCached(projectRoot);
                if (state.cancelRequested) return;
                var rules = state.task.relevantRules;
                var skills = state.task.relevantSkills;
                var checklists = state.task.relevantChecklists;

                // If the project has no relevant standards at all, there's nothing
                // worth interviewing about - skip straight to finalize. The synthesised
                // prompt will still benefit from the codebase scan in the Constraints
                // section; the interview is purely a standards-driven phase.
                boolean noStandards = rules.isEmpty() && skills.isEmpty() && checklists.isEmpty();
                if (noStandards && state.task.round == 0) {
                    state.task.status.set("no engineering standards found - skipping interview");
                    state.task.readyToFinalize = true;
                    state.nav.currentScreen = AppState.Screen.TASK_RESULT;
                    return;
                }

                var response = taskAdvisor.askNextQuestion(
                    state.task.projectContext.stack(),
                    state.task.description,
                    state.task.turns.get(),
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
                    if (state.task.critiqueCount < CRITIC_CAP
                            && state.task.turns.get().size() >= MIN_ROUNDS_BEFORE_CRITIC) {
                        state.task.status.set("checking whether the local model has enough context...");
                        var verdict = taskAdvisor.critiqueReadiness(
                            state.task.projectContext.stack(),
                            state.task.description,
                            state.task.turns.get(),
                            rules, skills, checklists);
                        if (state.cancelRequested) return;
                        if (!verdict.equals(TaskAdvisorService.READY_TOKEN)) {
                            state.task.critiqueCount++;
                            state.task.currentQuestion.set(verdict);
                            state.task.status.set("");
                            return;
                        }
                    }
                    state.task.readyToFinalize = true;
                    state.nav.currentScreen = AppState.Screen.TASK_RESULT;
                } else {
                    state.task.currentQuestion.set(response);
                }
                state.task.status.set("");
            } catch (Exception e) {
                if (state.cancelRequested) return;
                state.task.error = true;
                state.task.status.set(buildLlmErrorMessage("interview", e));
            } finally {
                state.task.thinking = false;
                state.task.opStartedAtMs = 0L;
            }
        });
        state.task.questionFuture = taskQFuture;
    }

    /**
     * Load + scope-filter standards once per task and stash the result on the
     * task state. Subsequent interview turns reuse the cached lists instead of
     * re-reading the YAML pack and re-filtering on every dispatch. Re-derived
     * when the cache is null - that happens on task entry, after resetTaskFlow,
     * or after resetTaskForReuse (because new task tags / opt-outs change the
     * scope-filter result).
     */
    private void ensureStandardsCached(Path projectRoot) {
        if (state.task.relevantRules != null
                && state.task.relevantSkills != null
                && state.task.relevantChecklists != null) {
            return;
        }
        var allRules = standardsLoader.loadRules(projectRoot);
        var allSkills = standardsLoader.loadSkills(projectRoot);
        var allChecklists = standardsLoader.loadChecklists(projectRoot);
        var stack = state.task.projectContext == null ? null : state.task.projectContext.stack();
        var relevant = TaskAdvisorService.selectRelevantStandards(
            stack, state.task.description, state.task.turns.get(),
            allRules, allSkills, allChecklists);
        state.task.relevantRules = relevant.rules();
        state.task.relevantSkills = relevant.skills();
        state.task.relevantChecklists = relevant.checklists();
    }

    /**
     * Fires when the user lands on the result screen with no synthesised prompt yet.
     * Runs finalize() on a background thread and writes the result to
     * {@code <projectRoot>/.launchpad/tasks/<ts>-<slug>.md}.
     */
    private void triggerTaskFinalizeIfNeeded() {
        if (state.nav.currentScreen != AppState.Screen.TASK_RESULT) return;
        if (state.task.thinking || !state.task.finalPrompt.isEmpty()) return;
        if (state.task.projectContext == null) return;

        state.task.thinking = true;
        state.task.error = false;
        state.task.opStartedAtMs = System.currentTimeMillis();
        state.task.status.set("synthesising the prompt from the local model...");

        var taskFFuture = backgroundExecutor.submit(() -> {
            try {
                var projectRoot = Path.of(state.projectPath).toAbsolutePath();
                ensureStandardsCached(projectRoot);
                if (state.cancelRequested) return;
                var result = taskAdvisor.synthesise(
                    state.task.projectContext,
                    state.task.description,
                    state.task.turns.get(),
                    state.task.relevantRules,
                    state.task.relevantSkills,
                    state.task.relevantChecklists,
                    chunk -> {
                        if (state.cancelRequested) throw new CancelledException();
                        state.task.finalPrompt = chunk.isEmpty() ? "" : chunk;
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
                    state.task.description,
                    virtualProjectContextStore.load(projectRoot).orElse(null));
                if (!grounding.markdown().isBlank()) {
                    grounded = grounded + "\n\n" + grounding.markdown();
                }
                state.task.finalPrompt = grounded;
                state.task.warnings = new java.util.ArrayList<>(result.warnings());

                // Save the prompt to disk + an `<!-- interview -->` appendix that
                // captures the raw task description and Q/A transcript so the
                // source of the synthesised prompt is preserved. Failures here
                // are non-fatal; the user can still copy from the TUI.
                try {
                    var taskDir = projectRoot.resolve(".launchpad").resolve("tasks");
                    Files.createDirectories(taskDir);
                    var ts = LocalDateTime.now().format(TASK_TS_FMT);
                    var slug = slugify(state.task.description);
                    var file = taskDir.resolve(ts + "-" + slug + ".md");
                    var fullContent = grounded + "\n\n" + renderInterviewAppendix(
                        state.task.description, state.task.turns.get(), ts);
                    Files.writeString(file, fullContent);
                    state.task.savedPath = file.toString();
                    state.task.status.set("");
                } catch (Exception writeFail) {
                    state.task.status.set("displayed but could not save: " + writeFail.getMessage());
                }
            } catch (CancelledException | java.util.concurrent.CancellationException ignored) {
                // User-requested cancel - the view already navigated to Welcome.
            } catch (Exception e) {
                if (state.cancelRequested) return;
                state.task.error = true;
                state.task.status.set(buildLlmErrorMessage("synthesise", e));
                // Clear any partial stream so the user sees the error instead of a half-prompt.
                state.task.finalPrompt = "";
            } finally {
                state.task.thinking = false;
                state.task.opStartedAtMs = 0L;
            }
        });
        state.task.finalizeFuture = taskFFuture;
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
     * of the synthesised prompt is traceable later.
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
     * the scan / audit / generation flow continues regardless.
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
     * in the active standards pack carry a {@code check} block.
     */
    private void runAuditPhase(com.acltabontabon.launchpad.scanner.ProjectContext ctx, Path projectRoot) {
        try {
            beginPhase(AppState.Phase.AUDIT_STANDARDS, 26, "Auditing project against standards...");
            var lastFindings = new java.util.concurrent.atomic.AtomicInteger(0);
            var result = auditService.run(ctx, projectRoot, progress -> {
                state.scan.message.set("Auditing: " + progress.ruleId()
                    + " (" + progress.index() + "/" + progress.total() + ")");
                state.scan.currentItem.set("rule " + progress.index() + " of " + progress.total()
                    + "  ·  " + progress.ruleId());
                state.scan.statsRulesTotal.set(progress.total());
                // Smooth the gauge from 26 -> 60 across the rules.
                int span = 60 - 26;
                int p = 26 + (int) (span * (progress.index() / (double) Math.max(1, progress.total())));
                state.scan.progress.set(Math.min(60, p));
                state.scan.pushActivity("audit", "checking " + progress.ruleId()
                    + "  (" + progress.index() + "/" + progress.total() + ")");
                lastFindings.set(state.scan.auditFindingsCount.get());
            });
            state.scan.auditRulesEvaluated.set(result.rulesAudited());
            state.scan.auditFindingsCount.set(result.findings().size());
            var counts = result.countsBySeverity();
            state.scan.auditMustCount.set(counts.getOrDefault("must", 0L).intValue()
                + counts.getOrDefault("never", 0L).intValue());
            state.scan.auditShouldCount.set(counts.getOrDefault("should", 0L).intValue());
            state.scan.auditMarkdownPath = result.markdownPath() == null ? null : result.markdownPath().toString();
            state.scan.auditSarifPath = result.sarifPath() == null ? null : result.sarifPath().toString();
            state.scan.currentItem.set("");
            int findings = result.findings().size();
            if (findings == 0 && result.rulesAudited() > 0) {
                state.scan.pushActivity("audit",
                    "all " + result.rulesAudited() + " rules clean", "success");
            } else if (findings > 0) {
                state.scan.pushActivity("audit",
                    findings + " findings  ·  "
                        + state.scan.auditMustCount.get() + " must, "
                        + state.scan.auditShouldCount.get() + " should",
                    "finding");
            }
        } catch (RuntimeException e) {
            state.scan.message.set("Audit skipped: " + e.getMessage());
            state.scan.pushActivity("audit", "skipped: " + e.getMessage(), "warn");
        }
    }

    /**
     * Checks the cancel flag. When set, resets scan state and routes back to Welcome.
     * Returns true if the caller should stop processing immediately.
     */
    private boolean checkCancelled() {
        if (!state.cancelRequested) return false;
        state.resetScanFlow();
        state.nav.currentScreen = AppState.Screen.WELCOME;
        return true;
    }

    /**
     * Returns true when any cause in the exception chain is a {@link CancelledException}.
     */
    private static boolean isCancelCause(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            if (t instanceof CancelledException) return true;
            if (t == t.getCause()) return false;
        }
        return false;
    }

    private void beginPhase(AppState.Phase phase, int baseProgress, String message) {
        state.scan.currentPhase.set(phase);
        state.scan.progress.set(baseProgress);
        state.scan.message.set(message);
        state.scan.streamedChunks.set(0);
        state.scan.streamTail.set("");
        state.scan.phaseStartedAtMs.set(System.currentTimeMillis());
    }

    private void onAiChunk(String chunk, int from, int to) {
        int n = state.scan.streamedChunks.incrementAndGet();
        int range = to - from;
        // diminishing returns: approaches `to` asymptotically so the bar always advances
        int p = from + (int) (range * (1 - Math.exp(-n / 80.0)));
        state.scan.progress.set(Math.min(to, p));
        state.scan.streamTail.set(appendTail(state.scan.streamTail.get(), chunk, 120));
    }

    private static String appendTail(String prev, String chunk, int max) {
        var combined = (prev + chunk).replaceAll("\\s+", " ");
        return combined.length() <= max ? combined : combined.substring(combined.length() - max);
    }

    private View currentView() {
        return switch (state.nav.currentScreen) {
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
