package com.acltabontabon.launchpad.tui.state;

import com.acltabontabon.launchpad.template.FilePlan;
import com.acltabontabon.launchpad.template.GeneratedFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class GenerationState {

    public volatile List<GeneratedFile> files = new ArrayList<>();
    public volatile int fileIndex = 0;
    public volatile List<FilePlan> plans = new ArrayList<>();
    public volatile List<String> warnings = new ArrayList<>();
    public volatile boolean showDiff = false;
    public volatile int previewScroll = 0;
    public final AtomicReference<String> saveStatus = new AtomicReference<>("");
    public volatile boolean saveError = false;
    public final Set<Integer> savedFileIndices = ConcurrentHashMap.newKeySet();

    public void nextFile() {
        if (!files.isEmpty()) {
            fileIndex = (fileIndex + 1) % files.size();
            previewScroll = 0;
        }
    }

    public void prevFile() {
        if (!files.isEmpty()) {
            fileIndex = (fileIndex - 1 + files.size()) % files.size();
            previewScroll = 0;
        }
    }

    public GeneratedFile currentFile() {
        if (files.isEmpty()) return null;
        return files.get(fileIndex);
    }

    public FilePlan currentPlan() {
        if (plans.isEmpty() || fileIndex >= plans.size()) return null;
        return plans.get(fileIndex);
    }

    public void resetSaveState() {
        savedFileIndices.clear();
        saveStatus.set("");
        saveError = false;
    }

    public void reset() {
        files = new ArrayList<>();
        plans = new ArrayList<>();
        warnings = new ArrayList<>();
        showDiff = false;
        fileIndex = 0;
        previewScroll = 0;
        resetSaveState();
    }
}
