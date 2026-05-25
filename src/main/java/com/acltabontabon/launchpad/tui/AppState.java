package com.acltabontabon.launchpad.tui;

import com.acltabontabon.launchpad.ai.LlmProvider;
import com.acltabontabon.launchpad.ai.LlmProviderStatus;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.RemoteStandardsStatus;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.task.TaskTurn;
import com.acltabontabon.launchpad.template.FilePlan;
import com.acltabontabon.launchpad.template.GeneratedFile;
import com.acltabontabon.launchpad.tui.mcp.AiClient;
import com.acltabontabon.launchpad.tui.mcp.ClientId;
import com.acltabontabon.launchpad.tui.mcp.WriteReport;
import com.acltabontabon.launchpad.tui.view.SettingsMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared mutable state between the TUI render loop (main thread) and background
 * scan/generation tasks. All fields are volatile or atomic for safe cross-thread reads.
 */
public class AppState {

    public enum Screen {
        WELCOME,
        PROJECT_SELECT,
        SCANNING,
        REVIEW,
        TASK_INPUT,
        TASK_INTERVIEW,
        TASK_RESULT,
        // SETTINGS / PROJECTS are off the linear flow - reached from WELCOME via the
        // command palette. Keep them last so the stepper's ordinal-indexed highlight
        // covers only the in-flow screens.
        SETTINGS,
        PROJECTS
    }

    public enum Phase {
        SCAN_FILES,
        AUDIT_STANDARDS,
        ASSEMBLE,
        DONE
    }

    // Navigation
    public volatile Screen currentScreen = Screen.WELCOME;

    // Local-AI provider readiness (updated from background thread)
    public final AtomicReference<LlmProviderStatus> ollamaStatus =
        new AtomicReference<>(LlmProviderStatus.checking());
    public volatile boolean healthCheckRequested = true;

    // Remote standards repo readiness (updated from background thread)
    public final AtomicReference<RemoteStandardsStatus> remoteStandardsStatus =
        new AtomicReference<>(RemoteStandardsStatus.checking());
    public volatile boolean remoteStandardsCheckRequested = true;

    // User selections
    public volatile String projectPath = "";
    // Project-picker filter state. The picker fuzzy-filters the union of
    // ProjectRegistry recents and ProjectDiscovery hits by the query string;
    // cursor is clamped to the filtered list size on render.
    public volatile String projectPickerQuery = "";
    public volatile int projectPickerCursor = 0;

    // Live-updated by ProjectSelectView on every keystroke that mutates projectPath, and
    // again on ENTER. True if <projectPath>/.launchpad/standards/ exists right now.
    public volatile boolean launchpadAware = detectLaunchpadAware(projectPath);

    // Set by ProjectSelectView when the support gate rejects the entered project
    // (e.g. not a Spring Boot Maven project). Rendered inline under the path input
    // and cleared on the next keystroke that mutates projectPath.
    public volatile String projectGateError = null;

    /**
     * Cheap filesystem probe: does the given path host a `.launchpad/standards/` directory?
     * Sub-microsecond after OS dentry cache warmup; safe to call from the TUI key handler on
     * every keystroke. Returns false for null / empty / non-existent / non-directory paths.
     */
    public static boolean detectLaunchpadAware(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            return Files.isDirectory(Path.of(path, ".launchpad", "standards"));
        } catch (Exception e) {
            return false;
        }
    }

    // Scan progress (updated from background thread)
    public final AtomicInteger scanProgress = new AtomicInteger(0);
    public final AtomicReference<String> scanMessage = new AtomicReference<>("Waiting...");
    public final AtomicReference<Phase> currentPhase = new AtomicReference<>(Phase.SCAN_FILES);
    public final AtomicInteger streamedChunks = new AtomicInteger(0);
    public final AtomicLong phaseStartedAtMs = new AtomicLong(0);
    public final AtomicReference<String> streamTail = new AtomicReference<>("");
    public volatile boolean scanComplete = false;
    public volatile boolean scanError = false;
    public volatile String scanErrorMessage = null;

    // Currently-processing item (file or rule id). Rendered under the progress
    // gauge so the user sees concrete forward motion, not just a percentage.
    public final AtomicReference<String> currentItem = new AtomicReference<>("");

    // Running stats surfaced in the scan view's stats card.
    public final AtomicInteger statsFilesScanned = new AtomicInteger(0);
    public final AtomicInteger statsPackages = new AtomicInteger(0);
    public final AtomicInteger statsDependencies = new AtomicInteger(0);
    public final AtomicInteger statsRulesTotal = new AtomicInteger(0);

    // Bounded activity log shown at the bottom of the scan view. Producers
    // (scanner / audit progress) push lines via {@link #pushActivity}; the
    // render loop reads a snapshot every frame. Capped at ACTIVITY_LOG_CAP
    // entries; oldest evicted on overflow.
    public static final int ACTIVITY_LOG_CAP = 200;

    public record ActivityEvent(long timestampMs, String phase, String message, String severity) {}

    private final Deque<ActivityEvent> activityLog = new ArrayDeque<>();
    private final Object activityLock = new Object();

    public void pushActivity(String phase, String message, String severity) {
        var evt = new ActivityEvent(System.currentTimeMillis(), phase, message, severity);
        synchronized (activityLock) {
            activityLog.addLast(evt);
            while (activityLog.size() > ACTIVITY_LOG_CAP) activityLog.pollFirst();
        }
    }

    public void pushActivity(String phase, String message) {
        pushActivity(phase, message, "info");
    }

    public List<ActivityEvent> activitySnapshot() {
        synchronized (activityLock) {
            return new ArrayList<>(activityLog);
        }
    }

    public void clearActivity() {
        synchronized (activityLock) {
            activityLog.clear();
        }
        activityScrollOffset = 0;
    }

    // Activity log scroll: number of entries to step back from the latest.
    // 0 = pinned to newest; new events are always visible. >0 = user has
    // scrolled up and rendering shows older entries; new events still land
    // in the ring but don't disturb the viewport.
    public volatile int activityScrollOffset = 0;

    // Audit phase (updated from background thread). When no rules carry a `check`
    // block, the audit is skipped and these fields stay zero / null.
    public final AtomicInteger auditFindingsCount = new AtomicInteger(0);
    public final AtomicInteger auditMustCount = new AtomicInteger(0);
    public final AtomicInteger auditShouldCount = new AtomicInteger(0);
    public final AtomicInteger auditRulesEvaluated = new AtomicInteger(0);
    public volatile String auditMarkdownPath = null;
    public volatile String auditSarifPath = null;

    // Generated output
    public volatile List<GeneratedFile> generatedFiles = new ArrayList<>();
    public volatile int reviewFileIndex = 0;
    // Per-file write plans (existence check + action). Parallel to generatedFiles.
    public volatile List<FilePlan> filePlans = new ArrayList<>();
    // Validation warnings from the LLM phases (missing sections, hallucinated paths, retries).
    public volatile List<String> generationWarnings = new ArrayList<>();
    // Review screen: whether the preview pane is showing the diff vs the existing file.
    public volatile boolean reviewShowDiff = false;
    // Review screen: scroll offset (in lines) into the Markdown preview pane. Reset
    // when the selected file changes or when toggling diff mode.
    public volatile int reviewPreviewScroll = 0;

    // Review screen: save-all feedback (shown in the action bar)
    public final AtomicReference<String> reviewSaveStatus = new AtomicReference<>("");
    public volatile boolean reviewSaveError = false;
    // Indices into generatedFiles whose plan was applied to disk successfully in
    // this session. ReviewView swaps these rows' chips from the planned action
    // (NEW / MERGE / OVERWRITE) to a "SAVED" pill so the user sees post-save
    // truth instead of stale intent. Concurrent set: the TUI render loop calls
    // contains() at ~12 fps while the save action thread calls add(); a plain
    // HashSet would risk lost updates or CME during iteration.
    public final Set<Integer> savedFileIndices = ConcurrentHashMap.newKeySet();

    // Active Ollama model name (e.g. "qwen2.5-coder:7b"). Surfaced in the Welcome
    // header so the user knows which local model will do the work. Updated on
    // startup and whenever LaunchpadSettings.LlmProviderSettingsChanged fires.
    public volatile String activeModel = "";

    // Settings screen input state (provider + base URL + model + api key + remote standards URL)
    public volatile LlmProvider settingsProviderInput = LlmProvider.AUTO;
    public volatile String settingsBaseUrlInput = "";
    public volatile String settingsModelInput = "";
    public volatile String settingsApiKeyInput = "";
    public volatile String settingsRemoteStandardsUrlInput = "";
    // 0 = provider, 1 = base URL, 2 = model, 3 = api key, 4 = remote standards URL, 5 = "Connect to AI tool" action row
    public volatile int settingsFocusIndex = 0;
    public volatile String settingsErrorMessage = null;

    // /settings sub-mode. Default FIELDS = the three property inputs; the
    // MCP_* modes drive the "Connect to AI tool" flow that writes Launchpad's
    // MCP server snippet into Claude / Cursor config files.
    public volatile SettingsMode settingsMode = SettingsMode.FIELDS;
    public final AtomicReference<List<AiClient>> mcpClients = new AtomicReference<>(new ArrayList<>());
    public final AtomicReference<Set<ClientId>> mcpSelected = new AtomicReference<>(new HashSet<>());
    public final AtomicReference<List<WriteReport>> mcpReports = new AtomicReference<>(new ArrayList<>());
    public volatile int mcpSelectionIndex = 0;
    // Backup dir reported by the writer (null when nothing was backed up).
    public volatile String mcpBackupDir = null;

    // Welcome screen command palette
    public volatile String commandInput = "";
    public volatile int commandCursorIndex = 0;
    public volatile String welcomeFlashMessage = "";

    // Projects screen
    public volatile int projectsCursorIndex = 0;
    public volatile String projectsFlashMessage = "";

    // /new-task flow state. taskFlow gates the post-scan branch in LaunchpadRunner -
    // when true, the pipeline scans only and routes to TASK_INPUT instead of generating
    // context files. The ProjectContext from the scan is stashed here so the advisor
    // service has codebase grounding without re-scanning.
    public volatile boolean taskFlow = false;
    public volatile ProjectContext taskProjectContext = null;
    public volatile String taskDescription = "";
    public volatile String taskCurrentAnswer = "";
    public volatile int taskRound = 0;
    public final AtomicReference<List<TaskTurn>> taskTurns = new AtomicReference<>(new ArrayList<>());
    public final AtomicReference<String> taskCurrentQuestion = new AtomicReference<>("");
    public final AtomicReference<String> taskStatus = new AtomicReference<>("");
    public volatile String taskFinalPrompt = "";
    public volatile String taskSavedPath = "";
    // True while a background LLM call (askNextQuestion / finalize) is in flight.
    // Views read this to lock input and show "thinking..." status.
    public volatile boolean taskThinking = false;
    // True once the interview has reached __DONE__ or the user pressed `f` - the next
    // tick should transition to TASK_RESULT and kick off finalize().
    public volatile boolean taskReadyToFinalize = false;
    // True when the last LLM call failed. Views render this distinctly (red, no
    // spinner) and surface taskStatus as the error message.
    public volatile boolean taskError = false;
    // When the currently-active LLM op started; used by views to render elapsed time
    // so the user knows the call isn't hung. 0 = no op active.
    public volatile long taskOpStartedAtMs = 0L;

    // Standards loaded + scope-filtered once at TASK_INTERVIEW entry, then reused
    // across every interview turn and the finalize call. Avoids re-reading the YAML
    // pack and re-running scopeApplies on every dispatch. Set to null on task reset
    // so the next task re-derives them against the new task description.
    public volatile List<Rule> taskRelevantRules = null;
    public volatile List<Skill> taskRelevantSkills = null;
    public volatile List<Checklist> taskRelevantChecklists = null;
    // Warnings from the synthesised-prompt validator. Surfaced by TaskResultView
    // as a banner so a malformed model output is visible to the user, not silent.
    public volatile List<String> taskWarnings = new ArrayList<>();

    // Cancellation token. Set by ESC in scan/task views; checked at phase
    // boundaries and in progress/chunk callbacks so the background thread
    // exits cleanly without a full stack-trace error.
    public volatile boolean cancelRequested = false;

    // Handles to in-flight background futures. Views call f.cancel(true) on ESC
    // so blocking I/O (Ollama HTTP) is interrupted promptly. Null when idle.
    public volatile Future<?> currentScanFuture = null;
    public volatile Future<?> currentTaskQuestionFuture = null;
    public volatile Future<?> currentTaskFinalizeFuture = null;

    // When true, ScanProgressView renders a "press q again to quit / ESC to cancel"
    // confirmation banner. Set by the global q handler mid-scan; cleared by ESC or
    // a second q.
    public volatile boolean quitConfirmPending = false;

    // Scan-trigger latch consulted by LaunchpadRunner.triggerScanIfNeeded(). The
    // runner sets it true when a scan starts so subsequent ticks don't fire a
    // second one; flows that need a fresh scan (e.g. starting /init or /new-task
    // from the command palette) reset it via resetScanLatch().
    public volatile boolean scanStarted = false;

    public void resetScanLatch() {
        scanStarted = false;
        scanComplete = false;
        scanError = false;
        scanErrorMessage = null;
        scanProgress.set(0);
        currentPhase.set(Phase.SCAN_FILES);
        streamedChunks.set(0);
        streamTail.set("");
        scanMessage.set("Waiting...");
        // A fresh scan produces a fresh file list; the previous run's "SAVED"
        // chips no longer apply to whatever GeneratedFile sits at index N now.
        savedFileIndices.clear();
        reviewSaveStatus.set("");
        reviewSaveError = false;
        auditFindingsCount.set(0);
        auditMustCount.set(0);
        auditShouldCount.set(0);
        auditRulesEvaluated.set(0);
        auditMarkdownPath = null;
        auditSarifPath = null;
        currentItem.set("");
        statsFilesScanned.set(0);
        statsPackages.set(0);
        statsDependencies.set(0);
        statsRulesTotal.set(0);
        clearActivity();
    }

    /**
     * Clears cancellation state and the scan future after a cancel-to-Welcome
     * transition. Resets the scan latch so the next pass through Scanning fires
     * a fresh run.
     */
    public void resetScanFlow() {
        cancelRequested = false;
        quitConfirmPending = false;
        currentScanFuture = null;
        resetScanLatch();
        generatedFiles = new ArrayList<>();
        filePlans = new ArrayList<>();
        generationWarnings = new ArrayList<>();
        reviewShowDiff = false;
        reviewFileIndex = 0;
        reviewPreviewScroll = 0;
    }

    /** Clear all per-scan review state so leaving Review back to Welcome lets a
     *  follow-up scan start clean. Resets the scan latch too so the next pass
     *  through Scanning fires a fresh generation. */
    public void resetReviewFlow() {
        resetScanLatch();
        generatedFiles = new ArrayList<>();
        filePlans = new ArrayList<>();
        generationWarnings = new ArrayList<>();
        reviewShowDiff = false;
        reviewFileIndex = 0;
        reviewPreviewScroll = 0;
    }

    public void resetTaskFlow() {
        cancelRequested = false;
        currentTaskQuestionFuture = null;
        currentTaskFinalizeFuture = null;
        taskFlow = false;
        taskProjectContext = null;
        taskDescription = "";
        taskCurrentAnswer = "";
        taskRound = 0;
        taskTurns.set(new ArrayList<>());
        taskCurrentQuestion.set("");
        taskStatus.set("");
        taskFinalPrompt = "";
        taskSavedPath = "";
        taskThinking = false;
        taskReadyToFinalize = false;
        taskError = false;
        taskOpStartedAtMs = 0L;
        taskRelevantRules = null;
        taskRelevantSkills = null;
        taskRelevantChecklists = null;
        taskWarnings = new ArrayList<>();
    }

    /** Clear per-task state but keep the scanned ProjectContext so a follow-up task
     *  on the same project doesn't trigger a re-scan. The cached standards lists
     *  are cleared too because the new task's tags / opt-outs will be different. */
    public void resetTaskForReuse() {
        taskDescription = "";
        taskCurrentAnswer = "";
        taskRound = 0;
        taskTurns.set(new ArrayList<>());
        taskCurrentQuestion.set("");
        taskStatus.set("");
        taskFinalPrompt = "";
        taskSavedPath = "";
        taskThinking = false;
        taskReadyToFinalize = false;
        taskError = false;
        taskOpStartedAtMs = 0L;
        taskRelevantRules = null;
        taskRelevantSkills = null;
        taskRelevantChecklists = null;
        taskWarnings = new ArrayList<>();
    }

    public void nextReviewFile() {
        if (!generatedFiles.isEmpty()) {
            reviewFileIndex = (reviewFileIndex + 1) % generatedFiles.size();
            reviewPreviewScroll = 0;
        }
    }

    public void prevReviewFile() {
        if (!generatedFiles.isEmpty()) {
            reviewFileIndex = (reviewFileIndex - 1 + generatedFiles.size()) % generatedFiles.size();
            reviewPreviewScroll = 0;
        }
    }

    public GeneratedFile currentReviewFile() {
        if (generatedFiles.isEmpty()) return null;
        return generatedFiles.get(reviewFileIndex);
    }

    public FilePlan currentFilePlan() {
        if (filePlans.isEmpty() || reviewFileIndex >= filePlans.size()) return null;
        return filePlans.get(reviewFileIndex);
    }
}
