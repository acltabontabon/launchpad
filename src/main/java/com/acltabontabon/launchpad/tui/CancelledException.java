package com.acltabontabon.launchpad.tui;

/**
 * Thrown from progress/chunk callbacks when the user requests cancellation while a
 * background scan or AI-generation task is in flight. Extends RuntimeException so it
 * threads through {@code Files.walkFileTree}'s IOException chain and Reactor's
 * {@code .blockLast()} without requiring checked-exception declarations on every
 * callback interface.
 */
public class CancelledException extends RuntimeException {

    public CancelledException() {
        super("Cancelled by user.");
    }
}
