package com.acltabontabon.launchpad.tui.command;

import com.acltabontabon.launchpad.tui.AppState;
import dev.tamboui.tui.TuiRunner;

public record Command(String id, String label, String description, CommandAction action) {

    @FunctionalInterface
    public interface CommandAction {
        void execute(AppState state, TuiRunner runner);
    }
}
