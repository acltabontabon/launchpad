package com.acltabontabon.launchpad.inventory;

import com.acltabontabon.launchpad.model.VirtualProjectContext;
import com.acltabontabon.launchpad.model.graph.ProjectModel;
import com.acltabontabon.launchpad.standards.index.StandardsIndex;
import com.acltabontabon.launchpad.support.LaunchpadVersion;
import com.acltabontabon.launchpad.template.ProvenanceHeader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a project's prepared artifacts and provenance stamp and produces a
 * single, deterministic {@link ReadinessResult} - "is this project ready, stale,
 * partial, missing, or broken, and why?" - from just the project-root path.
 *
 * <p>Strictly read-only: it never creates, modifies, or deletes a file, and it
 * never throws for a project on disk. A corrupt or unreadable artifact is logged
 * at WARN and surfaced as {@link ReadinessStatus#ERROR}.
 *
 * <p>The evaluator decides status, reasons, and recommended action; it does not
 * format for any specific surface (UI, CLI, MCP) - that is the Readiness View
 * Model's job. It currently emits five statuses; {@link ReadinessStatus#UNSUPPORTED}
 * and {@link ReadinessStatus#IGNORED} are reserved for a later layer.
 */
@Component
public class ReadinessEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ReadinessEvaluator.class);

    private static final String LAUNCHPAD_DIR = ".launchpad";
    private static final String AGENTS_FILE = "AGENTS.md";
    private static final String CONTEXT_SIDECAR = "project-context.json";
    private static final String MODEL_SIDECAR = "project.model.json";
    private static final String STANDARDS_SIDECAR = "standards.index.json";

    /** Root build files whose mtime, if newer than the stamp, marks output stale. */
    private static final List<String> BUILD_FILES =
        List.of("pom.xml", "build.gradle", "build.gradle.kts");

    private final SidecarReader sidecars = new SidecarReader();

    /** Evaluates readiness for {@code projectRoot}. Never throws for an on-disk project. */
    public ReadinessResult evaluate(Path projectRoot) {
        var agents = projectRoot.resolve(AGENTS_FILE);
        var contextSidecar = projectRoot.resolve(LAUNCHPAD_DIR).resolve(CONTEXT_SIDECAR);
        var modelSidecar = projectRoot.resolve(LAUNCHPAD_DIR).resolve(MODEL_SIDECAR);
        var standardsSidecar = projectRoot.resolve(LAUNCHPAD_DIR).resolve(STANDARDS_SIDECAR);

        // 1 + 2: presence. Nothing prepared -> MISSING; some-but-not-all -> PARTIAL.
        var missing = new ArrayList<String>();
        addIfMissing(missing, agents, AGENTS_FILE);
        addIfMissing(missing, contextSidecar, LAUNCHPAD_DIR + "/" + CONTEXT_SIDECAR);
        addIfMissing(missing, modelSidecar, LAUNCHPAD_DIR + "/" + MODEL_SIDECAR);
        addIfMissing(missing, standardsSidecar, LAUNCHPAD_DIR + "/" + STANDARDS_SIDECAR);

        if (missing.size() == 4) {
            return result(ReadinessStatus.MISSING, RecommendedAction.PREPARE,
                List.of("No preparation output found."), null);
        }
        if (!missing.isEmpty()) {
            var reasons = missing.stream().map(name -> "Missing artifact: " + name).toList();
            return result(ReadinessStatus.PARTIAL, RecommendedAction.REFRESH, reasons, null);
        }

        // 3: any present artifact unreadable or corrupt -> ERROR.
        var corrupt = new ArrayList<String>();
        checkParseable(corrupt, contextSidecar, LAUNCHPAD_DIR + "/" + CONTEXT_SIDECAR, VirtualProjectContext.class);
        checkParseable(corrupt, modelSidecar, LAUNCHPAD_DIR + "/" + MODEL_SIDECAR, ProjectModel.class);
        checkParseable(corrupt, standardsSidecar, LAUNCHPAD_DIR + "/" + STANDARDS_SIDECAR, StandardsIndex.class);

        String agentsContent = readAgents(agents, corrupt);

        if (!corrupt.isEmpty()) {
            log.warn("Unreadable preparation artifacts under {}: {}", projectRoot, corrupt);
            var reasons = corrupt.stream().map(name -> "Unreadable artifact: " + name).toList();
            return result(ReadinessStatus.ERROR, RecommendedAction.REFRESH, reasons, null);
        }

        // 4: provenance stamp missing or unparseable -> STALE (never ERROR).
        var provenance = ProvenanceHeader.parse(agentsContent).orElse(null);
        if (provenance == null) {
            return result(ReadinessStatus.STALE, RecommendedAction.REFRESH,
                List.of("No readable provenance stamp in " + AGENTS_FILE + "."), null);
        }

        var lastPreparedAt = parseInstant(provenance.generatedAt());

        // 5: Launchpad version drift since preparation -> STALE.
        var current = LaunchpadVersion.current();
        if (!current.equals(provenance.launchpadVersion())) {
            return result(ReadinessStatus.STALE, RecommendedAction.REFRESH,
                List.of("Launchpad version changed: " + provenance.launchpadVersion() + " -> " + current + "."),
                lastPreparedAt);
        }

        // 6: a build file edited after preparation -> STALE. An unparseable stamp
        // timestamp is itself a staleness signal (we cannot trust the lineage).
        if (lastPreparedAt == null) {
            return result(ReadinessStatus.STALE, RecommendedAction.REFRESH,
                List.of("Provenance timestamp is unparseable: \"" + provenance.generatedAt() + "\"."), null);
        }
        var staleBuild = newerBuildFiles(projectRoot, lastPreparedAt);
        if (!staleBuild.isEmpty()) {
            var reasons = staleBuild.stream()
                .map(name -> name + " modified after last preparation.").toList();
            return result(ReadinessStatus.STALE, RecommendedAction.REFRESH, reasons, lastPreparedAt);
        }

        // 7: everything current and consistent.
        return result(ReadinessStatus.READY, RecommendedAction.NONE, List.of(), lastPreparedAt);
    }

    private void addIfMissing(List<String> missing, Path file, String label) {
        if (!sidecars.exists(file)) {
            missing.add(label);
        }
    }

    private void checkParseable(List<String> corrupt, Path file, String label, Class<?> type) {
        if (!sidecars.isParseable(file, type)) {
            corrupt.add(label);
        }
    }

    private String readAgents(Path agents, List<String> corrupt) {
        try {
            return Files.readString(agents);
        } catch (IOException e) {
            corrupt.add(AGENTS_FILE);
            return "";
        }
    }

    /** Names of root build files whose mtime is strictly after {@code preparedAt}. */
    private List<String> newerBuildFiles(Path projectRoot, Instant preparedAt) {
        var stale = new ArrayList<String>();
        for (var name : BUILD_FILES) {
            var file = projectRoot.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                if (Files.getLastModifiedTime(file).toInstant().isAfter(preparedAt)) {
                    stale.add(name);
                }
            } catch (IOException e) {
                log.warn("Could not read mtime of {} - skipping staleness check for it.", file, e);
            }
        }
        return stale;
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ReadinessResult result(ReadinessStatus status, RecommendedAction action,
                                          List<String> reasons, Instant lastPreparedAt) {
        return new ReadinessResult(status, reasons, action, lastPreparedAt);
    }
}
