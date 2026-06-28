package com.acltabontabon.launchpad.inventory;

import java.util.List;

/**
 * A presentation-ready projection of one project's readiness, assembled by
 * {@link ReadinessViewMapper} from project identity plus a {@link ReadinessResult}.
 *
 * <p>This is the contract every surface (interactive dashboard, CLI {@code --json},
 * MCP) renders against, so badges, reasons, and the valid-action menu are
 * identical everywhere. It carries no rendering concerns - no colors, no glyphs;
 * those belong to each surface.
 *
 * @param name             human-friendly project name
 * @param path             absolute path to the project root
 * @param typeLabel        short stack/type label, e.g. "Spring Boot / Maven",
 *                         "Git repo"
 * @param status           the readiness verdict to badge
 * @param reasonLines      specific causes behind the verdict, one per line;
 *                         empty when {@link ReadinessStatus#READY}
 * @param recommendedAction the single action a surface should highlight; the
 *                         mapper derives this deterministically from {@code status}
 * @param availableActions the ordered action menu for this status, excluding
 *                         actions invalid for it (and excluding the {@code NONE}
 *                         no-op); may be empty
 */
public record ProjectReadinessView(
    String name,
    String path,
    String typeLabel,
    ReadinessStatus status,
    List<String> reasonLines,
    RecommendedAction recommendedAction,
    List<RecommendedAction> availableActions
) {

    public ProjectReadinessView {
        reasonLines = reasonLines == null ? List.of() : List.copyOf(reasonLines);
        availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
    }
}
