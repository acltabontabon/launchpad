package com.acltabontabon.launchpad.tui.command;

import com.acltabontabon.launchpad.tui.AppState;

import java.util.List;

public final class CommandPalette {

    public static final List<Command> ALL = List.of(
        new Command(
            "/init",
            "Initialize project",
            "Generate AI context files for a project",
            (state, runner) -> {
                state.resetTaskFlow();
                state.resetScanLatch();
                state.nav.currentScreen = AppState.Screen.PROJECT_SELECT;
            }
        ),
        new Command(
            "/new-task",
            "New task",
            "Interview-driven prompt builder for Claude / Cursor",
            (state, runner) -> {
                state.resetTaskFlow();
                state.resetScanLatch();
                state.task.flow = true;
                state.nav.currentScreen = AppState.Screen.PROJECT_SELECT;
            }
        ),
        new Command(
            "/projects",
            "Projects",
            "Browse projects you have used Launchpad on",
            (state, runner) -> {
                state.projectsCursorIndex = 0;
                state.projectsFlashMessage = "";
                state.nav.currentScreen = AppState.Screen.PROJECTS;
            }
        ),
        new Command(
            "/settings",
            "Settings",
            "Configure LLM provider and remote standards repo",
            (state, runner) -> state.nav.currentScreen = AppState.Screen.SETTINGS
        ),
        new Command(
            "/quit",
            "Quit",
            "Exit Launchpad",
            (state, runner) -> runner.quit()
        )
    );

    private CommandPalette() {}

    public static List<Command> filter(String input) {
        if (input == null || input.isEmpty() || !input.startsWith("/")) {
            return List.of();
        }
        var needle = input.toLowerCase();
        return ALL.stream()
            .filter(c -> c.id().toLowerCase().startsWith(needle))
            .toList();
    }
}
