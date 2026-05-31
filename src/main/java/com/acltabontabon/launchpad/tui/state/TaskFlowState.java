package com.acltabontabon.launchpad.tui.state;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.task.TaskTurn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public class TaskFlowState {

    public volatile boolean flow = false;
    public volatile ProjectContext projectContext = null;
    public volatile String description = "";
    public volatile String currentAnswer = "";
    public volatile int round = 0;
    public final AtomicReference<List<TaskTurn>> turns = new AtomicReference<>(new ArrayList<>());
    public final AtomicReference<String> currentQuestion = new AtomicReference<>("");
    public final AtomicReference<String> status = new AtomicReference<>("");
    public volatile String finalPrompt = "";
    public volatile String savedPath = "";
    public volatile boolean thinking = false;
    public volatile boolean readyToFinalize = false;
    public volatile int critiqueCount = 0;
    public volatile boolean error = false;
    public volatile long opStartedAtMs = 0L;
    public volatile List<Rule> relevantRules = null;
    public volatile List<Skill> relevantSkills = null;
    public volatile List<Checklist> relevantChecklists = null;
    public volatile List<String> warnings = new ArrayList<>();
    public volatile Future<?> questionFuture = null;
    public volatile Future<?> finalizeFuture = null;

    public void reset() {
        flow = false;
        projectContext = null;
        description = "";
        currentAnswer = "";
        round = 0;
        turns.set(new ArrayList<>());
        currentQuestion.set("");
        status.set("");
        finalPrompt = "";
        savedPath = "";
        thinking = false;
        readyToFinalize = false;
        error = false;
        opStartedAtMs = 0L;
        critiqueCount = 0;
        relevantRules = null;
        relevantSkills = null;
        relevantChecklists = null;
        warnings = new ArrayList<>();
        questionFuture = null;
        finalizeFuture = null;
    }

    public void resetForReuse() {
        description = "";
        currentAnswer = "";
        round = 0;
        turns.set(new ArrayList<>());
        currentQuestion.set("");
        status.set("");
        finalPrompt = "";
        savedPath = "";
        thinking = false;
        readyToFinalize = false;
        error = false;
        opStartedAtMs = 0L;
        critiqueCount = 0;
        relevantRules = null;
        relevantSkills = null;
        relevantChecklists = null;
        warnings = new ArrayList<>();
    }
}
