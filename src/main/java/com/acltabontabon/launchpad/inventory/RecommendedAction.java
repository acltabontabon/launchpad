package com.acltabontabon.launchpad.inventory;

/**
 * The single action a surface should recommend to move a project toward
 * {@link ReadinessStatus#READY}, paired with a {@link ReadinessResult}.
 *
 * <p>{@link #REBUILD_MODEL} and {@link #RESOLVE_STANDARDS} are part of the
 * forward public contract but are not emitted yet; the evaluator currently maps
 * every actionable status to {@link #PREPARE} or {@link #REFRESH}.
 */
public enum RecommendedAction {

    /** Nothing prepared yet - run a first preparation. */
    PREPARE,

    /** Re-run preparation to refresh stale, partial, or corrupt output. */
    REFRESH,

    /** Rebuild only the project-model graph sidecar. Reserved - not emitted yet. */
    REBUILD_MODEL,

    /** Resolve the standards pack. Reserved - not emitted yet. */
    RESOLVE_STANDARDS,

    /** No action needed. */
    NONE
}
