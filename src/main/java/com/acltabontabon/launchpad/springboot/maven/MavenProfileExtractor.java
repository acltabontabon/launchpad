package com.acltabontabon.launchpad.springboot.maven;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of Maven `<profile>` blocks from `pom.xml` content.
 * Pattern-based (no XML parser); silently returns an empty list when the
 * input is missing, malformed, or has no profiles.
 * <p>
 * For each profile we capture the `<id>`, a short activation label, and a
 * handful of distinctive flags (argLine / jvmArgs tokens, native-image
 * buildArgs, PGO flags, skip flags). The output is the input for the
 * deterministic build-profiles table in the generated AGENTS.md.
 */
public final class MavenProfileExtractor {

    private static final Pattern PROFILE_BLOCK = Pattern.compile(
        "<profile\\b[^>]*>(.*?)</profile>", Pattern.DOTALL);
    private static final Pattern ID_TAG = Pattern.compile("<id>(.*?)</id>", Pattern.DOTALL);
    private static final Pattern ACTIVATION_BLOCK = Pattern.compile(
        "<activation\\b[^>]*>(.*?)</activation>", Pattern.DOTALL);
    private static final Pattern ACTIVE_BY_DEFAULT = Pattern.compile(
        "<activeByDefault>\\s*true\\s*</activeByDefault>", Pattern.CASE_INSENSITIVE);
    private static final Pattern JDK = Pattern.compile("<jdk>(.*?)</jdk>", Pattern.DOTALL);
    private static final Pattern OS_NAME = Pattern.compile("<os>\\s*<name>(.*?)</name>", Pattern.DOTALL);
    private static final Pattern PROPERTY_BLOCK = Pattern.compile(
        "<property>\\s*<name>(.*?)</name>(?:\\s*<value>(.*?)</value>)?", Pattern.DOTALL);
    private static final Pattern ARG_LINE = Pattern.compile("<argLine>(.*?)</argLine>", Pattern.DOTALL);
    private static final Pattern JVM_ARGS = Pattern.compile("<jvmArgs>(.*?)</jvmArgs>", Pattern.DOTALL);
    private static final Pattern BUILD_ARG = Pattern.compile("<buildArg>(.*?)</buildArg>", Pattern.DOTALL);
    private static final Pattern SKIP_TAG = Pattern.compile("<(skip[A-Za-z]*)>\\s*true\\s*</\\1>", Pattern.DOTALL);
    private static final int MAX_FLAGS_PER_PROFILE = 8;

    /** Raw `<profile>` body capped per entry so the synthesis evidence packet stays bounded. */
    private static final int MAX_RAW_BLOCK_CHARS = 3_600;

    private MavenProfileExtractor() {}

    public static List<MavenProfile> extract(String pomXml) {
        return extractWithRaw(pomXml).profiles();
    }

    /**
     * Returns both the structured profile records AND a map of id → raw
     * `<profile>...</profile>` body (truncated to {@link #MAX_RAW_BLOCK_CHARS}).
     * The raw map is empty when no profiles match; missing ids are skipped.
     */
    public static ExtractResult extractWithRaw(String pomXml) {
        if (pomXml == null || pomXml.isBlank()) return new ExtractResult(List.of(), java.util.Map.of());
        var profiles = new ArrayList<MavenProfile>();
        var raw = new java.util.LinkedHashMap<String, String>();
        var matcher = PROFILE_BLOCK.matcher(pomXml);
        while (matcher.find()) {
            var body = matcher.group(1);
            var id = firstGroup(ID_TAG, body);
            if (id == null || id.isBlank()) continue;
            var trimmedId = id.strip();
            var activation = describeActivation(body);
            var flags = extractFlags(body);
            profiles.add(new MavenProfile(trimmedId, activation, flags));
            raw.put(trimmedId, body.length() > MAX_RAW_BLOCK_CHARS
                ? body.substring(0, MAX_RAW_BLOCK_CHARS) + "\n... (truncated)"
                : body);
        }
        return new ExtractResult(profiles, raw);
    }

    /** Carrier for {@link #extractWithRaw(String)}. */
    public record ExtractResult(List<MavenProfile> profiles, java.util.Map<String, String> rawBodies) {}

    private static String describeActivation(String body) {
        var activationBody = firstGroup(ACTIVATION_BLOCK, body);
        if (activationBody == null) return "";
        var bits = new ArrayList<String>();
        if (ACTIVE_BY_DEFAULT.matcher(activationBody).find()) bits.add("active by default");
        var jdk = firstGroup(JDK, activationBody);
        if (jdk != null && !jdk.isBlank()) bits.add("JDK " + jdk.strip());
        var os = firstGroup(OS_NAME, activationBody);
        if (os != null && !os.isBlank()) bits.add("OS " + os.strip());
        var propMatcher = PROPERTY_BLOCK.matcher(activationBody);
        if (propMatcher.find()) {
            var name = propMatcher.group(1).strip();
            var value = propMatcher.group(2);
            bits.add("property " + name + (value == null || value.isBlank() ? "" : "=" + value.strip()));
        }
        return String.join(", ", bits);
    }

    private static List<String> extractFlags(String body) {
        var flags = new LinkedHashSet<String>();
        addTokens(flags, firstGroup(ARG_LINE, body));
        addTokens(flags, firstGroup(JVM_ARGS, body));
        var buildArgs = BUILD_ARG.matcher(body);
        while (buildArgs.find() && flags.size() < MAX_FLAGS_PER_PROFILE) {
            addTokens(flags, buildArgs.group(1));
        }
        var skip = SKIP_TAG.matcher(body);
        while (skip.find() && flags.size() < MAX_FLAGS_PER_PROFILE) {
            flags.add(skip.group(1));
        }
        return new ArrayList<>(flags).stream()
            .limit(MAX_FLAGS_PER_PROFILE)
            .toList();
    }

    private static void addTokens(LinkedHashSet<String> flags, String value) {
        if (value == null) return;
        for (var token : value.split("\\s+")) {
            var t = token.strip();
            if (t.isEmpty()) continue;
            if (!t.startsWith("-") && !t.startsWith("--")) continue;
            flags.add(t);
            if (flags.size() >= MAX_FLAGS_PER_PROFILE) return;
        }
    }

    private static String firstGroup(Pattern p, String body) {
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : null;
    }
}
