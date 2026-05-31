package com.acltabontabon.launchpad.standards.infer;

import com.acltabontabon.launchpad.model.Confidence;
import com.acltabontabon.launchpad.model.DetectedPattern;
import com.acltabontabon.launchpad.model.Evidence;
import com.acltabontabon.launchpad.model.InferredStandard;
import com.acltabontabon.launchpad.model.Risk;
import com.acltabontabon.launchpad.model.RiskSeverity;
import com.acltabontabon.launchpad.scanner.ClassFact;
import com.acltabontabon.launchpad.scanner.ProjectClassFacts;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.workflow.CollaboratorGraph;
import com.acltabontabon.launchpad.workflow.CollaboratorGraph.Collaborators;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic engineering-standards inference (Phase 3 foundation).
 * <p>
 * Where the audit engine checks <em>declared</em> rules, this engine derives
 * <em>de-facto</em> standards from observed consistency: it detects an
 * architectural pattern, scores how prevalent it is, and surfaces the outliers
 * as drift {@link Risk}s a tech lead can act on.
 * <p>
 * The first pattern is controller-to-service delegation: a layered codebase
 * routes controllers through a service tier rather than letting them reach
 * repositories directly. Prevalence is the fraction of controllers that
 * delegate to at least one system; a controller that touches a data store with
 * no service in between is recorded as a drift risk.
 * <p>
 * When a pattern is highly prevalent but no declared rule already covers it,
 * the engine drafts an {@link InferredStandard} - a proposed rule a tech lead
 * can promote into their versioned pack. This is the on-ramp that makes
 * adopting the pack nearly free: Launchpad surfaces the conventions the code
 * already follows, the lead decides which to codify.
 * <p>
 * Pattern/risk detection is {@link Confidence#INFERRED}; the proposed-rule text
 * is {@link Confidence#LLM_SUGGESTED} (a template today, model-authored later).
 * <p>
 * Stateless and dependency-free, so the assembler holds one directly.
 */
public class StandardsInferer {

    /** Result of inference: observed patterns, suggested guardrails, and drift risks. */
    public record Inference(List<DetectedPattern> patterns,
                            List<InferredStandard> inferredStandards,
                            List<Risk> risks) {
        public static final Inference EMPTY = new Inference(List.of(), List.of(), List.of());
    }

    private static final String DELEGATION_PATTERN_ID = "controller-service-delegation";

    /**
     * Prevalence at or above which a pattern with no declared rule is worth
     * proposing as a guardrail. Below this the convention is not consistent
     * enough to codify without a human first deciding it is intentional.
     */
    private static final double SUGGEST_THRESHOLD = 0.6;

    /** Infer with no knowledge of declared rules - every prevalent pattern is a candidate guardrail. */
    public Inference infer(ProjectContext scan) {
        return infer(scan, Set.of());
    }

    /**
     * Infer against the ids of rules already declared in the project's pack, so
     * a pattern the team has already codified is not re-proposed.
     */
    public Inference infer(ProjectContext scan, Set<String> declaredRuleIds) {
        if (scan == null || scan.rootPath() == null || scan.rootPath().isBlank()) {
            return Inference.EMPTY;
        }
        Path root = Path.of(scan.rootPath());
        List<String> sources = safe(scan.sourceFiles());

        List<ClassFact> controllers = ProjectClassFacts
            .collect(root, sources, safe(scan.endpoints())).stream()
            .filter(f -> f.kind() == ClassFact.Kind.REST_CONTROLLER)
            .toList();
        if (controllers.isEmpty()) {
            return Inference.EMPTY;
        }

        CollaboratorGraph graph = CollaboratorGraph.build(root, sources);

        List<Evidence> delegating = new ArrayList<>();
        List<Risk> risks = new ArrayList<>();
        for (ClassFact controller : controllers) {
            Collaborators touches = graph.touches(controller.name());
            boolean hasService = !touches.systems().isEmpty();
            boolean reachesData = !touches.dataEffects().isEmpty();

            if (hasService) {
                delegating.add(Evidence.of(controller.relativePath(),
                    controller.name() + " -> " + String.join(", ", touches.systems())));
            } else if (reachesData) {
                risks.add(layeringDrift(controller, touches));
            }
        }

        double prevalence = (double) delegating.size() / controllers.size();
        var pattern = new DetectedPattern(
            DELEGATION_PATTERN_ID,
            "Controllers delegate to a service layer",
            prevalence,
            delegating,
            Confidence.INFERRED);

        List<InferredStandard> suggestions = suggestGuardrails(pattern, declaredRuleIds);

        return new Inference(List.of(pattern), suggestions, risks);
    }

    /**
     * Propose a guardrail for a pattern that is prevalent enough to be intentional
     * but not already declared. The id mirrors the pattern id so a later run can
     * see the team has since adopted it (the id will appear in declaredRuleIds).
     */
    private List<InferredStandard> suggestGuardrails(DetectedPattern pattern, Set<String> declaredRuleIds) {
        Set<String> declared = declaredRuleIds == null ? Set.of()
            : declaredRuleIds.stream().map(StandardsInferer::norm).collect(Collectors.toSet());
        if (pattern.prevalence() < SUGGEST_THRESHOLD || declared.contains(norm(pattern.id()))) {
            return List.of();
        }

        int pct = (int) Math.round(pattern.prevalence() * 100);
        String proposedRule = "[should] Controllers must delegate to a service layer and not access "
            + "repositories directly. Observed in " + pct + "% of controllers; codifying it keeps the "
            + "convention enforced as the codebase grows.";

        return List.of(new InferredStandard(
            pattern.id(),
            pattern.name(),
            pattern.prevalence(),
            pattern.exemplars(),
            proposedRule,
            Confidence.LLM_SUGGESTED));
    }

    /** Normalize a rule id for comparison: lowercase, alphanumerics only. */
    private static String norm(String id) {
        return id == null ? "" : id.toLowerCase().replaceAll("[^a-z0-9]+", "");
    }

    private Risk layeringDrift(ClassFact controller, Collaborators touches) {
        return new Risk(
            "layering-drift-" + slug(controller.name()),
            "layering",
            RiskSeverity.MEDIUM,
            controller.name() + " reaches a data store ("
                + String.join(", ", touches.dataEffects())
                + ") without going through a service layer.",
            List.of(Evidence.of(controller.relativePath(),
                controller.name() + " -> " + String.join(", ", touches.dataEffects()))),
            List.of(slug(controller.name())),
            "Introduce a service between the controller and the data store so business "
                + "logic does not live in the web tier.",
            Confidence.INFERRED);
    }

    private static String slug(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
