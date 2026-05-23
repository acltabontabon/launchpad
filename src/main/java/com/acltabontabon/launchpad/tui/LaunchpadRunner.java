package com.acltabontabon.launchpad.tui;

import com.acltabontabon.launchpad.ai.ContextGeneratorService;
import com.acltabontabon.launchpad.ai.OllamaHealthChecker;
import com.acltabontabon.launchpad.ai.OllamaStatus;
import com.acltabontabon.launchpad.scanner.ProjectScanner;
import com.acltabontabon.launchpad.standards.RemoteStandardsChecker;
import com.acltabontabon.launchpad.standards.RemoteStandardsStatus;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import com.acltabontabon.launchpad.task.TaskAdvisorService;
import com.acltabontabon.launchpad.template.ContextTemplateEngine;
import com.acltabontabon.launchpad.tui.components.Footer;
import com.acltabontabon.launchpad.tui.components.Header;
import com.acltabontabon.launchpad.tui.view.ProjectSelectView;
import com.acltabontabon.launchpad.tui.view.ReviewView;
import com.acltabontabon.launchpad.tui.view.ScanProgressView;
import com.acltabontabon.launchpad.tui.view.SettingsView;
import com.acltabontabon.launchpad.tui.view.TargetSelectView;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

@Component
public class LaunchpadRunner implements ApplicationRunner {

    private final AppState state = new AppState();

    private final WelcomeView welcomeView;
    private final ProjectSelectView projectSelectView;
    private final TargetSelectView targetSelectView;
    private final ScanProgressView scanProgressView;
    private final ReviewView reviewView;
    private final SettingsView settingsView;
    private final TaskInputView taskInputView;
    private final TaskInterviewView taskInterviewView;
    private final TaskResultView taskResultView;

    private final ProjectScanner scanner;
    private final ContextGeneratorService generatorService;
    private final OllamaHealthChecker healthChecker;
    private final RemoteStandardsChecker remoteStandardsChecker;
    private final ContextTemplateEngine templateEngine;
    private final TaskAdvisorService taskAdvisor;
    private final StandardsLoader standardsLoader;

    private static final DateTimeFormatter TASK_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int TASK_RUNAWAY_CAP = 15;

    public LaunchpadRunner(
        WelcomeView welcomeView,
        ProjectSelectView projectSelectView,
        TargetSelectView targetSelectView,
        ScanProgressView scanProgressView,
        ReviewView reviewView,
        SettingsView settingsView,
        TaskInputView taskInputView,
        TaskInterviewView taskInterviewView,
        TaskResultView taskResultView,
        ProjectScanner scanner,
        ContextGeneratorService generatorService,
        OllamaHealthChecker healthChecker,
        RemoteStandardsChecker remoteStandardsChecker,
        ContextTemplateEngine templateEngine,
        TaskAdvisorService taskAdvisor,
        StandardsLoader standardsLoader
    ) {
        this.welcomeView = welcomeView;
        this.projectSelectView = projectSelectView;
        this.targetSelectView = targetSelectView;
        this.scanProgressView = scanProgressView;
        this.reviewView = reviewView;
        this.settingsView = settingsView;
        this.taskInputView = taskInputView;
        this.taskInterviewView = taskInterviewView;
        this.taskResultView = taskResultView;
        this.scanner = scanner;
        this.generatorService = generatorService;
        this.healthChecker = healthChecker;
        this.remoteStandardsChecker = remoteStandardsChecker;
        this.templateEngine = templateEngine;
        this.taskAdvisor = taskAdvisor;
        this.standardsLoader = standardsLoader;
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

        // Global quit - q from any screen except text-input screens.
        // WELCOME accepts q as quit when the palette is closed; once the user has
        // opened the palette by typing '/', q is a filter character (e.g. /quit).
        // TASK_INPUT / TASK_INTERVIEW accept text (description, answers); TASK_RESULT
        // uses q as "back to welcome" instead of quit so the user can start another task.
        if (event instanceof KeyEvent key
                && key.isChar('q')
                && state.currentScreen != AppState.Screen.PROJECT_SELECT
                && state.currentScreen != AppState.Screen.SETTINGS
                && state.currentScreen != AppState.Screen.TASK_INPUT
                && state.currentScreen != AppState.Screen.TASK_INTERVIEW
                && state.currentScreen != AppState.Screen.TASK_RESULT
                && !(state.currentScreen == AppState.Screen.WELCOME
                     && state.commandInput.startsWith("/"))) {
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
     * When {@code state.taskFlow} is true, only the scan phase runs and the next screen
     * is TASK_INPUT - the /new-task flow doesn't generate context files.
     */
    private void triggerScanIfNeeded() {
        if (state.currentScreen != AppState.Screen.SCANNING || state.scanStarted) return;
        state.scanStarted = true;

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

                // Phase 2 - AI: project summary  (eases 30 -> 55 as tokens stream)
                beginPhase(AppState.Phase.GENERATE_SUMMARY, 30, "Generating project summary with local AI...");
                var summary = generatorService.generateProjectSummary(ctx,
                    chunk -> onAiChunk(chunk, 30, 55));

                // Phase 3 - AI: target-specific content  (eases 60 -> 85)
                beginPhase(AppState.Phase.GENERATE_TARGET, 60,
                    "Generating " + state.selectedTarget.displayName + " content...");
                var targetContent = generatorService.generateTargetSpecificContent(ctx, state.selectedTarget,
                    chunk -> onAiChunk(chunk, 60, 85));

                // Surface only the user-actionable validation signals (empty/short
                // output, hallucination strips). Internal retry detail is dropped -
                // the retry already produced the winning output, and "we retried"
                // isn't something the user can act on.
                var warnings = new java.util.ArrayList<String>();
                summary.warnings().forEach(w -> warnings.add("summary: " + w));
                targetContent.warnings().forEach(w -> warnings.add(state.selectedTarget.displayName.toLowerCase() + ": " + w));
                state.generationWarnings = warnings;

                // Phase 4 - assemble files
                beginPhase(AppState.Phase.ASSEMBLE, 90, "Assembling output files...");
                var files = templateEngine.buildFiles(ctx, state.selectedTarget, summary.content(), targetContent.content());
                state.generatedFiles = files;
                var projectRoot = java.nio.file.Path.of(state.projectPath).toAbsolutePath();
                var plans = new java.util.ArrayList<com.acltabontabon.launchpad.template.FilePlan>();
                for (var f : files) plans.add(com.acltabontabon.launchpad.template.FilePlan.compute(f, projectRoot));
                state.filePlans = plans;

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

        CompletableFuture.runAsync(() -> {
            try {
                var projectRoot = Path.of(state.projectPath).toAbsolutePath();
                var rules = standardsLoader.loadRules(projectRoot);
                var skills = standardsLoader.loadSkills(projectRoot);
                var checklists = standardsLoader.loadChecklists(projectRoot);

                // If the project has no standards configured at all, there's nothing
                // worth interviewing about - skip straight to finalize. The synthesised
                // prompt will still benefit from the codebase scan in the Constraints /
                // Context sections; the interview is purely a standards-driven phase.
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
                if (response.equals(TaskAdvisorService.DONE_TOKEN)
                        || response.contains(TaskAdvisorService.DONE_TOKEN)) {
                    state.taskReadyToFinalize = true;
                    state.currentScreen = AppState.Screen.TASK_RESULT;
                } else {
                    state.taskCurrentQuestion.set(response);
                }
                state.taskStatus.set("");
            } catch (Exception e) {
                state.taskError = true;
                state.taskStatus.set(buildLlmErrorMessage("interview", e));
            } finally {
                state.taskThinking = false;
                state.taskOpStartedAtMs = 0L;
            }
        });
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
        state.taskStatus.set("streaming the synthesised prompt from the local model...");

        CompletableFuture.runAsync(() -> {
            try {
                var projectRoot = Path.of(state.projectPath).toAbsolutePath();
                var rules = standardsLoader.loadRules(projectRoot);
                var skills = standardsLoader.loadSkills(projectRoot);
                var checklists = standardsLoader.loadChecklists(projectRoot);
                // Stream chunks into taskFinalPrompt so the user watches it materialise
                // rather than staring at a spinner for 30+ seconds.
                var prompt = taskAdvisor.finalize(
                    state.taskProjectContext,
                    state.taskDescription,
                    state.taskTurns.get(),
                    rules,
                    skills,
                    checklists,
                    chunk -> state.taskFinalPrompt = state.taskFinalPrompt + chunk
                );
                state.taskFinalPrompt = prompt;

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
                    var fullContent = prompt + "\n\n" + renderInterviewAppendix(
                        state.taskDescription, state.taskTurns.get(), ts);
                    Files.writeString(file, fullContent);
                    state.taskSavedPath = file.toString();
                    state.taskStatus.set("");
                } catch (Exception writeFail) {
                    state.taskStatus.set("displayed but could not save: " + writeFail.getMessage());
                }
            } catch (Exception e) {
                state.taskError = true;
                state.taskStatus.set(buildLlmErrorMessage("finalize", e));
                // Clear any partial stream so the user sees the error instead of a half-prompt.
                state.taskFinalPrompt = "";
            } finally {
                state.taskThinking = false;
                state.taskOpStartedAtMs = 0L;
            }
        });
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
            case TASK_INPUT     -> taskInputView;
            case TASK_INTERVIEW -> taskInterviewView;
            case TASK_RESULT    -> taskResultView;
            case SETTINGS       -> settingsView;
        };
    }
}
