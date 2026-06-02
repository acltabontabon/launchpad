package com.acltabontabon.launchpad.springboot.gradle;

import com.acltabontabon.launchpad.scanner.Dependency;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a {@code build.gradle} / {@code build.gradle.kts} into a structured
 * {@link GradleBuildModel} - the single deterministic Gradle entry point, the
 * counterpart of {@link com.acltabontabon.launchpad.springboot.maven.MavenModelParser}.
 *
 * <p>Gradle build files are Groovy/Kotlin source, so there is no practical
 * structured model to read; instead one regex set handles both DSLs (their
 * plugin/dependency syntax overlaps enough). Comments are stripped first so a
 * commented-out Spring Boot line never produces a false positive, keeping the
 * parse deterministic. Missing or unreadable input yields
 * {@link GradleBuildModel#empty()} / {@link Optional#empty()} rather than
 * throwing - downstream callers decide what an empty model means.
 */
public final class GradleBuildParser {

    /** {@code id 'x'}, {@code id "x"}, {@code id("x")} - captures the plugin id. */
    private static final Pattern PLUGIN_ID =
        Pattern.compile("\\bid\\s*[(\\s]\\s*[\"']([^\"']+)[\"']");
    /** Quoted {@code group:artifact[:version]} coordinate (Groovy and Kotlin). */
    private static final Pattern COORDINATE =
        Pattern.compile("[\"']([\\w.\\-]+:[\\w.\\-]+(?::[\\w.${}\\-]+)?)[\"']");
    /** {@code classpath 'g:a:v'} / {@code classpath("g:a:v")}. */
    private static final Pattern CLASSPATH =
        Pattern.compile("\\bclasspath\\s*[(\\s]\\s*[\"']([^\"']+)[\"']");

    public GradleBuildModel parse(String build) {
        if (build == null || build.isBlank()) return GradleBuildModel.empty();
        String src = stripComments(build);
        return new GradleBuildModel(
            allMatches(PLUGIN_ID, src),
            collectDependencies(src),
            allMatches(CLASSPATH, src));
    }

    public Optional<GradleBuildModel> parseAtRoot(Path projectRoot) {
        Path groovy = projectRoot.resolve("build.gradle");
        Path kotlin = projectRoot.resolve("build.gradle.kts");
        Path target = Files.isRegularFile(groovy) ? groovy
            : Files.isRegularFile(kotlin) ? kotlin : null;
        if (target == null) return Optional.empty();
        try {
            return Optional.of(parse(Files.readString(target)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static List<Dependency> collectDependencies(String src) {
        var out = new ArrayList<Dependency>();
        Matcher m = COORDINATE.matcher(src);
        while (m.find()) {
            String[] parts = m.group(1).split(":");
            String name = parts[0] + ":" + parts[1];
            String version = parts.length > 2 ? parts[2] : null;
            out.add(new Dependency(name, version, "runtime"));
        }
        return out;
    }

    private static List<String> allMatches(Pattern pattern, String src) {
        var out = new ArrayList<String>();
        Matcher m = pattern.matcher(src);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * Strip block then line comments so commented-out Spring Boot declarations
     * are never matched. Order matters: block comments first, then {@code //}.
     */
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)//.*$", "");
    }
}
