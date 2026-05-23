package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.scanner.SpringProfile;
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
 * Composes a Spring prompt from a base template plus per-sub-stack facet
 * sections. Selected by {@link PromptSelector} when the detected framework is
 * a Spring variant.
 * <p>
 * Base templates live at <code>prompts/spring/base/{summary,skills,rules}.txt</code>
 * and must end with a <code>PROJECT CONTEXT:</code> line; the composer inserts
 * facet additions immediately before that line. Each facet file at
 * <code>prompts/spring/facets/&lt;id&gt;.txt</code> carries three delimited
 * sections (<code>=== SUMMARY ===</code>, <code>=== SKILLS ===</code>,
 * <code>=== RULES ===</code>); only the section matching the requested kind
 * is pulled.
 */
@Component
public class SpringPromptComposer {

    private static final Logger log = LoggerFactory.getLogger(SpringPromptComposer.class);
    private static final String CONTEXT_MARKER = "PROJECT CONTEXT:";

    public String compose(PromptSelector.Kind kind, SpringProfile profile) {
        String base = readClasspath("prompts/spring/base/" + kindFilename(kind));
        List<String> facets = profile == null ? List.of() : profile.facets();
        var loaded = new ArrayList<String>();
        var sections = new StringBuilder();
        for (String facet : facets) {
            String section = readFacetSection(facet, kind);
            if (section != null && !section.isBlank()) {
                sections.append("\n").append(section.strip()).append("\n");
                loaded.add(facet);
            }
        }
        log.info("SpringPromptComposer: composed '{}' from base + {}", kind.name().toLowerCase(), loaded);
        return sections.length() == 0 ? base : insertBeforeContext(base, sections.toString());
    }

    /** Returns the body of the section matching {@code kind}, or null if absent. */
    String readFacetSection(String facet, PromptSelector.Kind kind) {
        String path = "prompts/spring/facets/" + facet + ".txt";
        var resource = new ClassPathResource(path);
        if (!resource.exists()) {
            log.warn("SpringPromptComposer: facet file missing for '{}' (path: {})", facet, path);
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
            default -> throw new IllegalArgumentException("Spring composer does not handle kind: " + kind);
        };
    }

    private static String kindMarker(PromptSelector.Kind kind) {
        return switch (kind) {
            case SUMMARY -> "SUMMARY";
            case SKILLS -> "SKILLS";
            case RULES -> "RULES";
            default -> throw new IllegalArgumentException("Spring composer does not handle kind: " + kind);
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
