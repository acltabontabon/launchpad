package com.acltabontabon.launchpad.tui.state;

import com.acltabontabon.launchpad.tui.AppState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ScanState {

    public final AtomicInteger progress = new AtomicInteger(0);
    public final AtomicReference<String> message = new AtomicReference<>("Waiting...");
    public final AtomicReference<AppState.Phase> currentPhase =
        new AtomicReference<>(AppState.Phase.SCAN_FILES);
    public final AtomicInteger streamedChunks = new AtomicInteger(0);
    public final AtomicLong phaseStartedAtMs = new AtomicLong(0);
    public final AtomicReference<String> streamTail = new AtomicReference<>("");
    public volatile boolean complete = false;
    public volatile boolean error = false;
    public volatile String errorMessage = null;

    public final AtomicReference<String> currentItem = new AtomicReference<>("");

    public final AtomicInteger statsFilesScanned = new AtomicInteger(0);
    public final AtomicInteger statsPackages = new AtomicInteger(0);
    public final AtomicInteger statsDependencies = new AtomicInteger(0);
    public final AtomicInteger statsRulesTotal = new AtomicInteger(0);

    public static final int ACTIVITY_LOG_CAP = 200;

    public record ActivityEvent(long timestampMs, String phase, String message, String severity) {}

    private final Deque<ActivityEvent> activityLog = new ArrayDeque<>();
    private final Object activityLock = new Object();
    public volatile int activityScrollOffset = 0;

    public final AtomicInteger auditFindingsCount = new AtomicInteger(0);
    public final AtomicInteger auditMustCount = new AtomicInteger(0);
    public final AtomicInteger auditShouldCount = new AtomicInteger(0);
    public final AtomicInteger auditRulesEvaluated = new AtomicInteger(0);
    public volatile String auditMarkdownPath = null;
    public volatile String auditSarifPath = null;

    public volatile boolean started = false;
    public volatile Future<?> future = null;

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

    public void resetLatch() {
        started = false;
        complete = false;
        error = false;
        errorMessage = null;
        progress.set(0);
        currentPhase.set(AppState.Phase.SCAN_FILES);
        streamedChunks.set(0);
        streamTail.set("");
        message.set("Waiting...");
        currentItem.set("");
        statsFilesScanned.set(0);
        statsPackages.set(0);
        statsDependencies.set(0);
        statsRulesTotal.set(0);
        auditFindingsCount.set(0);
        auditMustCount.set(0);
        auditShouldCount.set(0);
        auditRulesEvaluated.set(0);
        auditMarkdownPath = null;
        auditSarifPath = null;
        clearActivity();
    }
}
