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
                state.currentScreen = AppState.Screen.PROJECT_SELECT;
            }
        ),
        new Command(
            "/new-task",
            "New task",
            "Interview-driven prompt builder for Claude / Cursor (local AI)",
            (state, runner) -> {
                state.resetTaskFlow();
                state.resetScanLatch();
                state.taskFlow = true;
                state.currentScreen = AppState.Screen.PROJECT_SELECT;
            }
        ),
        new Command(
            "/projects",
            "Projects",
            "Browse projects you have used Launchpad on (MCP-addressable by name)",
            (state, runner) -> {
                state.projectsCursorIndex = 0;
                state.projectsFlashMessage = "";
                state.currentScreen = AppState.Screen.PROJECTS;
            }
        ),
        new Command(
            "/settings",
            "Settings",
            "Configure Ollama and remote standards repo",
            (state, runner) -> state.currentScreen = AppState.Screen.SETTINGS
        ),
        new Command(
            "/help",
            "Help",
            "Show available commands and key shortcuts",
            (state, runner) -> state.currentScreen = AppState.Screen.HELP
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
