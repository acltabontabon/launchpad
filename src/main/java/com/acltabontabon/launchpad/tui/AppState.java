package com.acltabontabon.launchpad.tui;

import com.acltabontabon.launchpad.ai.LlmProviderStatus;
import com.acltabontabon.launchpad.standards.RemoteStandardsStatus;
import com.acltabontabon.launchpad.tui.state.GenerationState;
import com.acltabontabon.launchpad.tui.state.NavigationState;
import com.acltabontabon.launchpad.tui.state.ScanState;
import com.acltabontabon.launchpad.tui.state.SettingsDraftState;
import com.acltabontabon.launchpad.tui.state.TaskFlowState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared mutable state between the TUI render loop (main thread) and background
 * scan/generation tasks. Domain concerns are split into five focused components;
 * cross-cutting fields (project selection, provider health) live here.
 */
public class AppState {

    public enum Screen {
        WELCOME,
        PROJECT_SELECT,
        PROJECTION_SELECT,
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

    public final NavigationState nav = new NavigationState();
    public final ScanState scan = new ScanState();
    public final GenerationState gen = new GenerationState();
    public final SettingsDraftState settings = new SettingsDraftState();
    public final TaskFlowState task = new TaskFlowState();

    // Local-AI provider readiness (updated from background thread)
    public final AtomicReference<LlmProviderStatus> llmProviderStatus =
        new AtomicReference<>(LlmProviderStatus.checking());
    public volatile boolean healthCheckRequested = true;

    // Remote standards repo readiness (updated from background thread)
    public final AtomicReference<RemoteStandardsStatus> remoteStandardsStatus =
        new AtomicReference<>(RemoteStandardsStatus.checking());
    public volatile boolean remoteStandardsCheckRequested = true;

    // User selections
    public volatile String projectPath = "";
    // Project-picker filter state.
    public volatile String projectPickerQuery = "";
    public volatile int projectPickerCursor = 0;

    // Projection picker state.
    public volatile java.util.Set<String> projectionPickerSelected = new java.util.LinkedHashSet<>();
    public volatile int projectionPickerCursor = 0;
    public volatile boolean projectionPickerReturnsToSettings = false;

    // Live-updated by ProjectSelectView on every keystroke.
    public volatile boolean launchpadAware = detectLaunchpadAware(projectPath);

    // Set by ProjectSelectView when the support gate rejects the entered project.
    public volatile String projectGateError = null;

    // Active model name. Surfaced in the Welcome header.
    public volatile String activeModel = "";

    // Projects screen
    public volatile int projectsCursorIndex = 0;
    public volatile String projectsFlashMessage = "";

    // Cancellation token. Set by ESC in scan/task views.
    public volatile boolean cancelRequested = false;

    // Quit confirmation state.
    public volatile boolean quitConfirmPending = false;

    /**
     * Cheap filesystem probe: does the given path host a `.launchpad/standards/` directory?
     */
    public static boolean detectLaunchpadAware(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            return Files.isDirectory(Path.of(path, ".launchpad", "standards"));
        } catch (Exception e) {
            return false;
        }
    }

    public void resetScanLatch() {
        scan.resetLatch();
        gen.resetSaveState();
    }

    public void resetScanFlow() {
        cancelRequested = false;
        quitConfirmPending = false;
        scan.future = null;
        scan.resetLatch();
        gen.reset();
    }

    public void resetReviewFlow() {
        scan.resetLatch();
        gen.reset();
    }

    public void resetTaskFlow() {
        cancelRequested = false;
        task.reset();
    }

    public void resetTaskForReuse() {
        task.resetForReuse();
    }
}
