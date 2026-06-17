package com.acltabontabon.launchpad.standards;

import java.nio.file.Path;
import org.springframework.lang.Nullable;

/**
 * Thrown when a {@code standards-pack.yml} manifest declares a {@code schemaVersion}
 * this Launchpad cannot read - either the field is missing (it is required) or its
 * value falls outside the supported {@code [min, max]} range.
 *
 * <p>The structured fields (manifest path, found version, supported range) are kept
 * alongside the message so callers - notably the MCP layer - can build a typed error
 * envelope with machine-readable {@code details} without re-parsing the message.
 */
public class IncompatiblePackSchemaException extends RuntimeException {

    private final transient Path manifestFile;
    @Nullable
    private final Integer foundVersion;
    private final int minSupported;
    private final int maxSupported;
    private final String remediation;

    public IncompatiblePackSchemaException(Path manifestFile, @Nullable Integer foundVersion,
                                           int minSupported, int maxSupported) {
        super(buildMessage(manifestFile, foundVersion, minSupported, maxSupported));
        this.manifestFile = manifestFile;
        this.foundVersion = foundVersion;
        this.minSupported = minSupported;
        this.maxSupported = maxSupported;
        this.remediation = buildRemediation(foundVersion, minSupported, maxSupported);
    }

    private static String buildMessage(Path manifestFile, @Nullable Integer foundVersion,
                                       int min, int max) {
        String pack = "standards-pack.yml at " + manifestFile;
        if (foundVersion == null) {
            return pack + " is missing the required schemaVersion field; "
                + "this Launchpad supports " + range(min, max) + ".";
        }
        return pack + " declares schemaVersion " + foundVersion
            + " but this Launchpad supports " + range(min, max) + ".";
    }

    private static String buildRemediation(@Nullable Integer foundVersion, int min, int max) {
        if (foundVersion == null) {
            return "Add 'schemaVersion: " + max + "' to the manifest.";
        }
        if (foundVersion > max) {
            return "Upgrade Launchpad to read this pack, or lower the pack's schemaVersion to "
                + range(min, max) + ".";
        }
        return "Migrate the pack to schemaVersion " + range(min, max)
            + ", or use a Launchpad version that still supports schemaVersion " + foundVersion + ".";
    }

    private static String range(int min, int max) {
        return min == max ? Integer.toString(min) : min + ".." + max;
    }

    public Path manifestFile() {
        return manifestFile;
    }

    @Nullable
    public Integer foundVersion() {
        return foundVersion;
    }

    public int minSupported() {
        return minSupported;
    }

    public int maxSupported() {
        return maxSupported;
    }

    public String remediation() {
        return remediation;
    }

    /** Human-readable supported range, e.g. {@code "1"} or {@code "1..3"}. */
    public String supportedRange() {
        return range(minSupported, maxSupported);
    }
}
