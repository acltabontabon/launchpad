package com.acltabontabon.launchpad.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-call budgets for the {@code /new-task} pipeline. Independent from the
 * global {@code launchpad.ai.read-timeout} because interview turns are
 * inherently shorter (one focused question) than the synthesise call (a
 * full multi-section markdown doc), and the user-experience answer is
 * different for each: a stalled interview turn should fail fast so the
 * interview can recover; a stalled synthesise should be given the global
 * read-timeout's worth of patience before declaring the model dead.
 */
@ConfigurationProperties("launchpad.task")
public record LaunchpadTaskProperties(Duration interviewTimeout, Duration finalizeTimeout) {

    public LaunchpadTaskProperties {
        if (interviewTimeout == null) interviewTimeout = Duration.ofSeconds(60);
        if (finalizeTimeout == null) finalizeTimeout = Duration.ofSeconds(120);
    }
}
