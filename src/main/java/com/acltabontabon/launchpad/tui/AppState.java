package com.acltabontabon.launchpad.tui;

import com.acltabontabon.launchpad.ai.OllamaStatus;
import com.acltabontabon.launchpad.template.ContextTarget;
import com.acltabontabon.launchpad.template.GeneratedFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
        // SETTINGS is off the linear flow - reached from WELCOME via 'c'. Keep last
        // so the stepper's ordinal-indexed highlight covers only the in-flow screens.
        SETTINGS
    }

    // Navigation
    public volatile Screen currentScreen = Screen.WELCOME;

    // Ollama readiness (updated from background thread)
    public final AtomicReference<OllamaStatus> ollamaStatus = new AtomicReference<>(OllamaStatus.checking());
    public volatile boolean healthCheckRequested = true;

    // User selections
    public volatile String projectPath = System.getProperty("user.home");
    public volatile ContextTarget selectedTarget = ContextTarget.CLAUDE;
    public volatile int targetCursorIndex = 0;

    // Scan progress (updated from background thread)
    public final AtomicInteger scanProgress = new AtomicInteger(0);
    public final AtomicReference<String> scanMessage = new AtomicReference<>("Waiting...");
    public volatile boolean scanComplete = false;
    public volatile boolean scanError = false;
    public volatile String scanErrorMessage = null;

    // Generated output
    public volatile List<GeneratedFile> generatedFiles = new ArrayList<>();
    public volatile int reviewFileIndex = 0;

    // Text input cursor for path input
    public volatile int inputCursorPos = 0;

    // Settings screen input state (Ollama base URL + model)
    public volatile String settingsBaseUrlInput = "";
    public volatile String settingsModelInput = "";
    public volatile int settingsFocusIndex = 0; // 0 = base URL, 1 = model
    public volatile String settingsErrorMessage = null;

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
}
