package com.acltabontabon.launchpad.inventory;

/**
 * The readiness verdict for a single project directory, derived from its
 * prepared artifacts and provenance stamp by {@link ReadinessEvaluator}.
 *
 * <p>{@link #UNSUPPORTED} and {@link #IGNORED} are part of the forward public
 * contract that surfaces (UI, CLI, MCP) render against, but the evaluator does
 * not emit them yet - that determination belongs to a later layer.
 */
public enum ReadinessStatus {

    /** All required artifacts present, current, and consistent. */
    READY,

    /** Prepared once, but a signal (version or build file) shows it is out of date. */
    STALE,

    /** Some required artifacts present, but not all. */
    PARTIAL,

    /** No preparation output found at all. */
    MISSING,

    /** Project type is not supported. Reserved - not emitted yet. */
    UNSUPPORTED,

    /** A present artifact is unreadable or corrupt. */
    ERROR,

    /** Project is explicitly excluded from readiness. Reserved - not emitted yet. */
    IGNORED
}
