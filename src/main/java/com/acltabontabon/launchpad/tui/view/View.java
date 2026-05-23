package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;

import java.util.List;

public interface View {

    /**
     * Renders this view into the given area of the frame.
     * Called on every render tick - must be fast and pure (no side effects).
     */
    void render(Frame frame, Rect area, AppState state);

    /**
     * Handles an input event. Returns true if the event was consumed.
     * May mutate AppState to trigger screen transitions.
     */
    boolean handleEvent(Event event, TuiRunner runner, AppState state);

    /**
     * Key hints this view contributes to the persistent footer. Default is none.
     * Views no longer render their own bottom hints bars - they declare hints
     * here and the runner-owned Footer composes them with status dots.
     */
    default List<KeyHint> footerHints(AppState state) {
        return List.of();
    }
}
