package com.acltabontabon.launchpad.tui;

import com.acltabontabon.launchpad.ai.OllamaStatus;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.RemoteStandardsStatus;
import com.acltabontabon.launchpad.task.TaskTurn;
import com.acltabontabon.launchpad.template.ContextTarget;
import com.acltabontabon.launchpad.template.FilePlan;
import com.acltabontabon.launchpad.template.GeneratedFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        TARGET_SELECT,
        SCANNING,
        REVIEW,
        TASK_INPUT,
        TASK_INTERVIEW,
        TASK_RESULT,
        // SETTINGS is off the linear flow - reached from WELCOME via 'c'. Keep last
        // so the stepper's ordinal-indexed highlight covers only the in-flow screens.
        SETTINGS
    }

    public enum Phase {
        SCAN_FILES,
        GENERATE_SUMMARY,
        GENERATE_TARGET,
        ASSEMBLE,
        DONE
    }

    // Navigation
    public volatile Screen currentScreen = Screen.WELCOME;

    // Ollama readiness (updated from background thread)
    public final AtomicReference<OllamaStatus> ollamaStatus = new AtomicReference<>(OllamaStatus.checking());
    public volatile boolean healthCheckRequested = true;

    // Remote standards repo readiness (updated from background thread)
    public final AtomicReference<RemoteStandardsStatus> remoteStandardsStatus =
        new AtomicReference<>(RemoteStandardsStatus.checking());
    public volatile boolean remoteStandardsCheckRequested = true;

    // User selections
    public volatile String projectPath = System.getProperty("user.home");
    public volatile String pathSuggestion = PathAutocomplete.suggest(projectPath);
    public volatile ContextTarget selectedTarget = ContextTarget.CLAUDE;
    public volatile int targetCursorIndex = 0;

    // Live-updated by ProjectSelectView on every keystroke that mutates projectPath, and
    // again on ENTER. True if <projectPath>/.launchpad/standards/ exists right now.
    public volatile boolean launchpadAware = detectLaunchpadAware(projectPath);

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

    // Generated output
    public volatile List<GeneratedFile> generatedFiles = new ArrayList<>();
    public volatile int reviewFileIndex = 0;
    // Per-file write plans (existence check + action). Parallel to generatedFiles.
    public volatile List<FilePlan> filePlans = new ArrayList<>();
    // Validation warnings from the LLM phases (missing sections, hallucinated paths, retries).
    public volatile List<String> generationWarnings = new ArrayList<>();
    // Review screen: whether the preview pane is showing the diff vs the existing file.
    public volatile boolean reviewShowDiff = false;

    // Review screen: save-all feedback (shown in the action bar)
    public final AtomicReference<String> reviewSaveStatus = new AtomicReference<>("");
    public volatile boolean reviewSaveError = false;

    // Text input cursor for path input
    public volatile int inputCursorPos = 0;

    // Settings screen input state (Ollama base URL + model + remote standards URL)
    public volatile String settingsBaseUrlInput = "";
    public volatile String settingsModelInput = "";
    public volatile String settingsRemoteStandardsUrlInput = "";
    public volatile int settingsFocusIndex = 0; // 0 = base URL, 1 = model, 2 = remote standards URL
    public volatile String settingsErrorMessage = null;

    // Welcome screen command palette
    public volatile String commandInput = "";
    public volatile int commandCursorIndex = 0;
    public volatile String welcomeFlashMessage = "";

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
    }

    public void resetTaskFlow() {
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
    }

    /** Clear per-task state but keep the scanned ProjectContext so a follow-up task
     *  on the same project doesn't trigger a re-scan. */
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
    }

    public void nextReviewFile() {
        if (!generatedFiles.isEmpty()) {
            reviewFileIndex = (reviewFileIndex + 1) % generatedFiles.size();
        }
    }

    public void prevReviewFile() {
        if (!generatedFiles.isEmpty()) {
            reviewFileIndex = (reviewFileIndex - 1 + generatedFiles.size()) % generatedFiles.size();
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
