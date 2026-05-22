package com.acltabontabon.launchpad.tui.command;

import com.acltabontabon.launchpad.tui.AppState;

import java.util.List;

public final class CommandPalette {

    public static final List<Command> ALL = List.of(
        new Command(
            "/init",
            "Initialize project",
            "Generate AI context files for a project",
            (state, runner) -> state.currentScreen = AppState.Screen.PROJECT_SELECT
        ),
        new Command(
            "/new-feature",
            "New feature",
            "Coming soon",
            (state, runner) -> state.welcomeFlashMessage = "Coming soon"
        ),
        new Command(
            "/settings",
            "Settings",
            "Configure Ollama base URL and model",
            (state, runner) -> state.currentScreen = AppState.Screen.SETTINGS
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
