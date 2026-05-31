package com.acltabontabon.launchpad.task;

import com.acltabontabon.launchpad.model.Risk;
import com.acltabontabon.launchpad.model.SystemComponent;
import com.acltabontabon.launchpad.model.VirtualProjectContext;
import com.acltabontabon.launchpad.model.Workflow;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Grounds a synthesised task in the virtualized project model.
 * <p>
 * Given the task description and the project model, it matches the task against
 * the workflows, systems, and risks Launchpad discovered, and renders an
 * "Execution context" markdown section the cloud agent reads before starting:
 * which systems the task likely affects, which workflows are relevant, and which
 * risks to watch. This is the core thesis in miniature - local synthesis does
 * the repeatable discovery so the cloud agent does not re-derive it from source.
 * <p>
 * Matching is deterministic keyword overlap over significant words; no model is
 * involved. Everything rendered traces back to a discovered model entry, so the
 * section cannot introduce systems or workflows that do not exist.
 */
public final class TaskModelGrounding {

    /** Words below this length are too generic to anchor a match. */
    private static final int MIN_WORD_LENGTH = 4;
    /** Caps so the section stays a brief orientation, not a data dump. */
    private static final int MAX_WORKFLOWS = 6;
    private static final int MAX_SYSTEMS = 8;

    private TaskModelGrounding() {}

    /** The matched slice of the model for a task, plus its rendered markdown. */
    public record Grounding(List<String> affectedSystems,
                            List<Workflow> relevantWorkflows,
                            List<Risk> riskWatchlist,
                            String markdown) {
        public boolean isEmpty() {
            return affectedSystems.isEmpty() && relevantWorkflows.isEmpty() && riskWatchlist.isEmpty();
        }
    }

    /**
     * Build the grounding for a task. Returns an empty grounding (blank markdown)
     * when the model is absent or nothing matches, so the caller can append
     * unconditionally without emitting an empty heading.
     */
    public static Grounding ground(String taskDescription, VirtualProjectContext model) {
        if (model == null) {
            return empty();
        }
        Set<String> words = significantWords(taskDescription);

        List<Workflow> workflows = matchWorkflows(words, safe(model.workflows()));
        List<String> systems = affectedSystems(words, safe(model.systems()), workflows);
        List<Risk> risks = riskWatchlist(safe(model.risks()), systems);

        Grounding grounding = new Grounding(systems, workflows, risks, "");
        if (grounding.isEmpty()) {
            return empty();
        }
        return new Grounding(systems, workflows, risks, render(systems, workflows, risks));
    }

    private static List<Workflow> matchWorkflows(Set<String> words, List<Workflow> workflows) {
        List<Workflow> matched = new ArrayList<>();
        for (Workflow w : workflows) {
            if (matched.size() >= MAX_WORKFLOWS) break;
            if (workflowMatches(words, w)) {
                matched.add(w);
            }
        }
        return matched;
    }

    private static boolean workflowMatches(Set<String> words, Workflow w) {
        if (overlaps(words, w.name())) return true;
        if (overlaps(words, w.trigger())) return true;
        for (String step : w.steps()) {
            if (overlaps(words, step)) return true;
        }
        for (String system : w.touchedSystems()) {
            if (overlaps(words, system)) return true;
        }
        return false;
    }

    /**
     * Systems the task likely affects: those named in the task description, plus
     * the systems touched by every matched workflow (so a task that names a
     * workflow inherits that workflow's reach).
     */
    private static List<String> affectedSystems(Set<String> words, List<SystemComponent> systems,
                                                List<Workflow> matchedWorkflows) {
        Set<String> out = new LinkedHashSet<>();
        for (SystemComponent s : systems) {
            if (out.size() >= MAX_SYSTEMS) break;
            if (overlaps(words, s.name())) {
                out.add(s.name());
            }
        }
        for (Workflow w : matchedWorkflows) {
            for (String touched : w.touchedSystems()) {
                if (out.size() >= MAX_SYSTEMS) break;
                out.add(touched);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Risks worth watching for this task: those whose affected systems intersect
     * the task's affected systems. When the task affects no known system, fall
     * back to the project-wide risks - they are concerns the agent should know
     * about regardless.
     */
    private static List<Risk> riskWatchlist(List<Risk> risks, List<String> affectedSystems) {
        if (risks.isEmpty()) {
            return List.of();
        }
        if (affectedSystems.isEmpty()) {
            return risks;
        }
        Set<String> affected = new LinkedHashSet<>();
        for (String s : affectedSystems) affected.add(norm(s));

        List<Risk> matched = new ArrayList<>();
        for (Risk r : risks) {
            boolean hit = r.affectedSystems().stream().map(TaskModelGrounding::norm).anyMatch(affected::contains);
            if (hit) {
                matched.add(r);
            }
        }
        // No system-specific risk matched - still surface the project-wide list.
        return matched.isEmpty() ? risks : matched;
    }

    private static String render(List<String> systems, List<Workflow> workflows, List<Risk> risks) {
        var sb = new StringBuilder();
        sb.append("## Execution context\n\n");
        sb.append("_Grounded in Launchpad's project model - verify against the code before relying on it._\n\n");

        if (!systems.isEmpty()) {
            sb.append("### Affected systems\n\n");
            for (String s : systems) {
                sb.append("- `").append(s).append("`\n");
            }
            sb.append("\n");
        }

        if (!workflows.isEmpty()) {
            sb.append("### Relevant workflows\n\n");
            for (Workflow w : workflows) {
                sb.append("- **").append(w.name()).append("**");
                if (w.trigger() != null && !w.trigger().isBlank()) {
                    sb.append(" - trigger: `").append(w.trigger()).append("`");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!risks.isEmpty()) {
            sb.append("### Risk watchlist\n\n");
            for (Risk r : risks) {
                sb.append("- **").append(r.severity().name().toLowerCase()).append("** [")
                    .append(r.category()).append("] ").append(r.description()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static boolean overlaps(Set<String> words, String text) {
        if (text == null || text.isBlank()) return false;
        for (String token : significantWords(text)) {
            if (words.contains(token)) return true;
        }
        return false;
    }

    private static Set<String> significantWords(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
            if (raw.length() >= MIN_WORD_LENGTH) {
                out.add(raw);
            }
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]+", "");
    }

    private static Grounding empty() {
        return new Grounding(List.of(), List.of(), List.of(), "");
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
