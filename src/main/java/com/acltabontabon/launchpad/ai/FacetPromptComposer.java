package com.acltabontabon.launchpad.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Composes a prompt for any framework with a base + facets layout. Selected by
 * {@link PromptSelector} when the detected framework has its own
 * {@code prompts/<framework>/} tree on the classpath.
 * <p>
 * Layout per framework:
 * <pre>
 *   prompts/&lt;framework&gt;/
 *     base/
 *       summary.txt   (always included)
 *       skills.txt
 *       rules.txt
 *     facets/
 *       &lt;facet-id&gt;.txt   (one per sub-stack signal)
 * </pre>
 * <p>
 * Base templates end with a {@code PROJECT CONTEXT:} line; the composer
 * inserts enabled facet sections immediately before that line. Each facet file
 * carries three delimited sections (<code>=== SUMMARY ===</code>,
 * <code>=== SKILLS ===</code>, <code>=== RULES ===</code>); only the section
 * matching the requested kind is pulled.
 * <p>
 * Adding a new framework requires no Java change in this class: drop a new
 * {@code prompts/&lt;framework&gt;/base+facets/} tree, add a profile record
 * exposing {@code facets()}, and add a routing branch in
 * {@link PromptSelector}.
 */
@Component
public class FacetPromptComposer {

    private static final Logger log = LoggerFactory.getLogger(FacetPromptComposer.class);
    private static final String CONTEXT_MARKER = "PROJECT CONTEXT:";

    public String compose(PromptSelector.Kind kind, String frameworkSlug, List<String> facetIds) {
        String base = readClasspath("prompts/" + frameworkSlug + "/base/" + kindFilename(kind));
        List<String> ids = facetIds == null ? List.of() : facetIds;
        var loaded = new ArrayList<String>();
        var sections = new StringBuilder();
        for (String facet : ids) {
            String section = readFacetSection(frameworkSlug, facet, kind);
            if (section != null && !section.isBlank()) {
                sections.append("\n").append(section.strip()).append("\n");
                loaded.add(facet);
            }
        }
        log.info("FacetPromptComposer: composed '{}/{}' from base + {}",
            frameworkSlug, kind.name().toLowerCase(), loaded);
        return sections.length() == 0 ? base : insertBeforeContext(base, sections.toString());
    }

    /** Returns the body of the section matching {@code kind}, or null if absent. */
    String readFacetSection(String frameworkSlug, String facet, PromptSelector.Kind kind) {
        String path = "prompts/" + frameworkSlug + "/facets/" + facet + ".txt";
        var resource = new ClassPathResource(path);
        if (!resource.exists()) {
            log.warn("FacetPromptComposer: facet file missing for '{}/{}' (path: {})",
                frameworkSlug, facet, path);
            return null;
        }
        String content = readClasspath(path);
        String marker = "=== " + kindMarker(kind) + " ===";
        int start = content.indexOf(marker);
        if (start < 0) return null;
        int bodyStart = content.indexOf('\n', start);
        if (bodyStart < 0) return null;
        bodyStart += 1;
        int nextSep = content.indexOf("=== ", bodyStart);
        return nextSep < 0 ? content.substring(bodyStart) : content.substring(bodyStart, nextSep);
    }

    private static String insertBeforeContext(String base, String insertion) {
        int idx = base.indexOf(CONTEXT_MARKER);
        if (idx < 0) return base + insertion;
        return base.substring(0, idx) + insertion + "\n" + base.substring(idx);
    }

    private static String kindFilename(PromptSelector.Kind kind) {
        return switch (kind) {
            case SUMMARY -> "summary.txt";
            case SKILLS -> "skills.txt";
            case RULES -> "rules.txt";
            default -> throw new IllegalArgumentException("Composer does not handle kind: " + kind);
        };
    }

    private static String kindMarker(PromptSelector.Kind kind) {
        return switch (kind) {
            case SUMMARY -> "SUMMARY";
            case SKILLS -> "SKILLS";
            case RULES -> "RULES";
            default -> throw new IllegalArgumentException("Composer does not handle kind: " + kind);
        };
    }

    private static String readClasspath(String location) {
        var resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new UncheckedIOException(new IOException("not found: " + location));
        }
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
