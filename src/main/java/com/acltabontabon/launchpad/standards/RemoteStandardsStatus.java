package com.acltabontabon.launchpad.standards;

import java.time.Duration;
import java.time.Instant;

public record RemoteStandardsStatus(State state, String message, String hint) {

    public enum State { CHECKING, NOT_CONFIGURED, SYNCED, STALE_CACHE, ERROR }

    public static RemoteStandardsStatus checking() {
        return new RemoteStandardsStatus(State.CHECKING, "Checking standards repo...", null);
    }

    public static RemoteStandardsStatus notConfigured() {
        return new RemoteStandardsStatus(
            State.NOT_CONFIGURED,
            "Standards: local only",
            null
        );
    }

    public static RemoteStandardsStatus synced(Instant fetchedAt) {
        return new RemoteStandardsStatus(
            State.SYNCED,
            "Standards: synced " + relative(fetchedAt) + " ago",
            null
        );
    }

    public static RemoteStandardsStatus staleCache(String reason) {
        return new RemoteStandardsStatus(
            State.STALE_CACHE,
            "Standards: offline (using cache)",
            reason
        );
    }

    public static RemoteStandardsStatus error(String reason) {
        return new RemoteStandardsStatus(
            State.ERROR,
            "Standards: fetch failed",
            reason
        );
    }

    private static String relative(Instant when) {
        var seconds = Duration.between(when, Instant.now()).getSeconds();
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) return (seconds / 3600) + "h";
        return (seconds / 86400) + "d";
    }
}
