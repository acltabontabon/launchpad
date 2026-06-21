package com.acltabontabon.launchpad.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolves the running Launchpad build version, independent of any UI layer so
 * the template and provenance layers can stamp it into generated files.
 *
 * <p>Resolution order: the Maven {@code build-info} plugin's
 * {@code /META-INF/build-info.properties} ({@code build.version}), then the jar
 * manifest's implementation version, then {@code "dev"} when neither is present
 * (e.g. running from an IDE).
 */
public final class LaunchpadVersion {

    private LaunchpadVersion() {}

    /** The raw version string, e.g. {@code "0.6.0-SNAPSHOT"} or {@code "dev"}. Never null. */
    public static String current() {
        var v = readBuildInfoVersion();
        if (v == null) {
            var pkg = LaunchpadVersion.class.getPackage();
            v = pkg == null ? null : pkg.getImplementationVersion();
        }
        return v == null ? "dev" : v;
    }

    private static String readBuildInfoVersion() {
        try (InputStream in = LaunchpadVersion.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in == null) return null;
            var props = new Properties();
            props.load(in);
            var v = props.getProperty("build.version");
            return (v == null || v.isBlank()) ? null : v;
        } catch (IOException e) {
            return null;
        }
    }
}
