package com.acltabontabon.launchpad.standards.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Provenance of a resolved standards set: which pack the rules came from and
 * which source directory won during resolution. Every rule emitted in a single
 * {@link StandardsIndex} shares one source, because the loader resolves the
 * whole rule set from a single winning source directory.
 *
 * @param pack    Pack id from the {@code standards-pack.yml} manifest; {@code null}
 *                in legacy flat (manifest-less) mode.
 * @param version Pack version from the manifest; {@code null} in flat mode.
 * @param origin  Which source directory the rules resolved from:
 *                {@code "remote-cache"} or {@code "local-override"}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StandardsSource(
    String pack,
    String version,
    String origin
) {

    /** Origin label for the shared remote standards-pack cache. */
    public static final String ORIGIN_REMOTE_CACHE = "remote-cache";

    /** Origin label for the per-project {@code .launchpad/standards} override. */
    public static final String ORIGIN_LOCAL_OVERRIDE = "local-override";
}
