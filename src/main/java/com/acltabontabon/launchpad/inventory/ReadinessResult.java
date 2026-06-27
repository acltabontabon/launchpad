package com.acltabontabon.launchpad.inventory;

import java.time.Instant;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * The deterministic readiness verdict for one project directory, produced by
 * {@link ReadinessEvaluator}. It carries the structured signals a surface needs
 * to render a badge, an explanation, and a call to action, but does not format
 * for any specific surface - that is the Readiness View Model's job.
 *
 * @param status            The readiness verdict.
 * @param reasonLines       Specific causes behind the verdict, one per line;
 *                          empty when {@link ReadinessStatus#READY}.
 * @param recommendedAction The single action that moves the project forward.
 * @param lastPreparedAt    When the project was last prepared, parsed from the
 *                          provenance stamp; {@code null} when unavailable or unparseable.
 */
public record ReadinessResult(
    ReadinessStatus status,
    List<String> reasonLines,
    RecommendedAction recommendedAction,
    @Nullable Instant lastPreparedAt
) {

    public ReadinessResult {
        if (reasonLines == null) reasonLines = List.of();
    }
}
