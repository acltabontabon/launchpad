package com.acltabontabon.launchpad.standards;

import org.springframework.stereotype.Component;

/**
 * Welcome-screen readiness probe for the remote standards repo.
 * Forces a git pull and reports the result for the badge.
 * Mirrors OllamaHealthChecker so LaunchpadRunner can trigger both the same way.
 */
@Component
public class RemoteStandardsChecker {

    private final RemoteStandardsFetcher fetcher;

    public RemoteStandardsChecker(RemoteStandardsFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public RemoteStandardsStatus check() {
        fetcher.refreshNow();
        return fetcher.lastStatus();
    }
}
