package com.acltabontabon.launchpad.tui.components;

import com.acltabontabon.launchpad.tui.theme.Icons;

/**
 * Centralised access to the braille spinner. Views call {@link #frame(long)} with
 * any monotonically-increasing counter (e.g. {@code System.currentTimeMillis() / 100}).
 */
public final class Spinner {

    private Spinner() {}

    public static String frame(long tickCounter) {
        int idx = (int) Math.floorMod(tickCounter, Icons.SPINNER.length);
        return Icons.SPINNER[idx];
    }
}
