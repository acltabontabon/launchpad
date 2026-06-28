package com.acltabontabon.launchpad.inventory;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns project identity plus a {@link ReadinessResult} into a
 * {@link ProjectReadinessView} for any surface to render.
 *
 * <p>This is the single place that decides which actions a status permits: both
 * the {@code recommendedAction} and the {@code availableActions} menu are derived
 * deterministically from {@link ReadinessStatus}, never copied from the
 * evaluator's pre-set action. Driving off status (not the evaluator's action)
 * lets the mapper also give the reserved {@link ReadinessStatus#UNSUPPORTED} and
 * {@link ReadinessStatus#IGNORED} statuses a defined contract, even though
 * {@link ReadinessEvaluator} never emits them.
 *
 * <p>For the five statuses the evaluator does emit, the derived
 * {@code recommendedAction} matches what it already chose, so no surface sees a
 * different action depending on its source.
 */
@Component
public class ReadinessViewMapper {

    /**
     * Builds the view for one project.
     *
     * @param name      human-friendly project name
     * @param path      absolute path to the project root
     * @param typeLabel short stack/type label, e.g. "Spring Boot / Maven"
     * @param readiness the deterministic verdict from {@link ReadinessEvaluator}
     */
    public ProjectReadinessView map(String name, String path, String typeLabel,
                                    ReadinessResult readiness) {
        var actions = actionsFor(readiness.status());
        return new ProjectReadinessView(
            name,
            path,
            typeLabel,
            readiness.status(),
            readiness.reasonLines(),
            actions.recommended(),
            actions.available()
        );
    }

    /**
     * The recommended action and action menu permitted for a status. The menu
     * omits the {@link RecommendedAction#NONE} no-op and any action invalid for
     * the status (notably {@link RecommendedAction#PREPARE} is never offered for
     * {@link ReadinessStatus#UNSUPPORTED} or {@link ReadinessStatus#IGNORED}).
     * The reserved {@link RecommendedAction#REBUILD_MODEL} and
     * {@link RecommendedAction#RESOLVE_STANDARDS} stay out of every menu until
     * an emitting layer exists.
     */
    private StatusActions actionsFor(ReadinessStatus status) {
        return switch (status) {
            case READY -> new StatusActions(
                RecommendedAction.NONE, List.of(RecommendedAction.REFRESH));
            case STALE, PARTIAL, ERROR -> new StatusActions(
                RecommendedAction.REFRESH, List.of(RecommendedAction.REFRESH));
            case MISSING -> new StatusActions(
                RecommendedAction.PREPARE, List.of(RecommendedAction.PREPARE));
            case UNSUPPORTED, IGNORED -> new StatusActions(
                RecommendedAction.NONE, List.of());
        };
    }

    private record StatusActions(RecommendedAction recommended,
                                 List<RecommendedAction> available) {
    }
}
