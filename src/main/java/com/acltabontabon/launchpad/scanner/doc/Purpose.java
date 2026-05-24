package com.acltabontabon.launchpad.scanner.doc;

/**
 * Coarse classification of what a documentation page is for. Used by MCP
 * clients to filter the doc index without having to read every page - e.g.
 * "show me the setup docs for this project" -> filter on {@link #SETUP}.
 * <p>
 * Deliberately small and stable: eight buckets cover the bulk of what real
 * Spring Boot projects ship. {@link #UNKNOWN} is the safe default when no
 * filename or path heuristic matches; the local-AI fallback (when enabled)
 * can promote unknowns to one of the named buckets.
 */
public enum Purpose {
    OVERVIEW,
    SETUP,
    ARCHITECTURE,
    API,
    OPERATIONS,
    CONTRIBUTION,
    CHANGELOG,
    UNKNOWN
}
