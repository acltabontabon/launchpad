package com.acltabontabon.launchpad.workflow;

import com.acltabontabon.launchpad.model.Confidence;
import com.acltabontabon.launchpad.model.Evidence;
import com.acltabontabon.launchpad.model.Workflow;
import com.acltabontabon.launchpad.model.WorkflowType;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import com.acltabontabon.launchpad.workflow.CollaboratorGraph.Collaborators;
import com.acltabontabon.launchpad.workflow.EventTriggerScanner.EventTrigger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers the business and operational workflows a project implements.
 * <p>
 * This is the deterministic foundation of the Project Virtualization Engine's
 * workflow story. It harvests two kinds of trigger signal and turns each into
 * a candidate {@link Workflow}:
 * <ul>
 *   <li>inbound HTTP endpoints (from the scan), grouped one per controller
 *       resource;</li>
 *   <li>non-HTTP triggers - scheduled jobs and message / event listeners -
 *       found in source by {@link EventTriggerScanner}.</li>
 * </ul>
 * Each workflow is {@link Confidence#DETERMINISTIC}: names are humanized from
 * the controller type or handler method, never invented.
 * <p>
 * Each workflow is then correlated through a {@link CollaboratorGraph} that
 * walks from the handler class to the systems, integrations, and data stores it
 * transitively touches, filling the {@code touchedSystems},
 * {@code externalCalls}, and {@code dataEffects} fields. A later phase adds a
 * bounded local-model pass that labels each candidate with a business purpose.
 * <p>
 * Stateless and dependency-free, so {@link ProjectContextAssembler} holds one
 * directly rather than wiring it as a bean.
 */
public class WorkflowDiscoverer {

    /** Discover workflows from a completed scan. Never returns null. */
    public List<Workflow> discover(ProjectContext scan) {
        if (scan == null) {
            return List.of();
        }
        CollaboratorGraph graph = buildGraph(scan);
        List<Workflow> workflows = new ArrayList<>(fromEndpoints(safe(scan.endpoints()), graph));
        workflows.addAll(fromEventTriggers(scan, graph));
        return workflows;
    }

    private CollaboratorGraph buildGraph(ProjectContext scan) {
        String root = scan.rootPath();
        if (root == null || root.isBlank()) {
            return CollaboratorGraph.build(null, List.of());
        }
        return CollaboratorGraph.build(Path.of(root), safe(scan.sourceFiles()));
    }

    /**
     * Scheduled jobs and message / event listeners discovered in source. Each
     * becomes its own workflow - these triggers do not share a resource grouping
     * the way HTTP routes do under a controller.
     */
    private List<Workflow> fromEventTriggers(ProjectContext scan, CollaboratorGraph graph) {
        String root = scan.rootPath();
        if (root == null || root.isBlank()) {
            return List.of();
        }
        var triggers = EventTriggerScanner.scan(Path.of(root), safe(scan.sourceFiles()));
        if (triggers.isEmpty()) {
            return List.of();
        }

        List<Workflow> workflows = new ArrayList<>();
        for (EventTrigger trigger : triggers) {
            workflows.add(toEventWorkflow(trigger, graph));
        }
        return workflows;
    }

    private Workflow toEventWorkflow(EventTrigger trigger, CollaboratorGraph graph) {
        String label = trigger.method().isBlank() ? trigger.annotation() : trigger.method();
        String name = humanizeMethod(label);
        String triggerText = "@" + trigger.annotation()
            + (trigger.method().isBlank() ? "" : " " + trigger.method() + "()");

        String evidenceDetail = "@" + trigger.annotation()
            + (trigger.method().isBlank() ? "" : " on " + trigger.method() + "()");

        Collaborators touches = graph.touches(classNameOf(trigger.file()));

        return new Workflow(
            slug(trigger.annotation() + "-" + label),
            name,
            "",
            trigger.type(),
            triggerText,
            List.of(triggerText),
            touches.systems(),
            touches.externalCalls(),
            touches.dataEffects(),
            Confidence.DETERMINISTIC,
            List.of(Evidence.of(trigger.file(), evidenceDetail))
        );
    }

    /**
     * Group HTTP endpoints by their owning controller into one inbound-API
     * workflow each. Endpoints whose handler does not name a controller are
     * grouped under a synthetic "HTTP API" bucket so no route is lost.
     */
    private List<Workflow> fromEndpoints(List<Endpoint> endpoints, CollaboratorGraph graph) {
        if (endpoints.isEmpty()) {
            return List.of();
        }

        // Preserve discovery order so output is stable across runs.
        Map<String, List<Endpoint>> byController = new LinkedHashMap<>();
        for (Endpoint endpoint : endpoints) {
            byController.computeIfAbsent(controllerOf(endpoint), key -> new ArrayList<>())
                .add(endpoint);
        }

        List<Workflow> workflows = new ArrayList<>();
        for (var entry : byController.entrySet()) {
            workflows.add(toWorkflow(entry.getKey(), entry.getValue(), graph));
        }
        return workflows;
    }

    private Workflow toWorkflow(String controller, List<Endpoint> endpoints, CollaboratorGraph graph) {
        List<String> steps = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            String route = endpoint.method() + " " + endpoint.path();
            steps.add(route);
            String handler = endpoint.handler() == null || endpoint.handler().isBlank()
                ? controller : endpoint.handler();
            evidence.add(Evidence.of(handler, route));
        }

        String name = humanize(controller);
        String trigger = endpoints.size() == 1
            ? steps.get(0)
            : endpoints.size() + " HTTP endpoints";

        Collaborators touches = graph.touches(controller);

        return new Workflow(
            slug(controller),
            name,
            "",                       // purpose - filled by the bounded model pass in a later phase
            WorkflowType.INBOUND_API,
            trigger,
            steps,
            touches.systems(),
            touches.externalCalls(),
            touches.dataEffects(),
            Confidence.DETERMINISTIC,
            evidence
        );
    }

    /** Controller simple name from a handler ("LoanController.decide" -> "LoanController"). */
    private static String controllerOf(Endpoint endpoint) {
        String handler = endpoint.handler();
        if (handler == null || handler.isBlank()) {
            return "HTTP API";
        }
        int dot = handler.indexOf('.');
        return dot > 0 ? handler.substring(0, dot) : handler;
    }

    /** "LoanDecisionController" -> "Loan Decision"; "HTTP API" passes through. */
    private static String humanize(String controller) {
        if (controller == null || controller.isBlank()) {
            return "HTTP API";
        }
        String base = controller.endsWith("Controller")
            ? controller.substring(0, controller.length() - "Controller".length())
            : controller;
        if (base.isBlank()) {
            return controller;
        }
        String spaced = base.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        return spaced.isBlank() ? controller : spaced;
    }

    /** "processDailyReport" -> "Process Daily Report"; "@KafkaListener" passes through cleanly. */
    private static String humanizeMethod(String label) {
        if (label == null || label.isBlank()) {
            return "Event handler";
        }
        String spaced = label
            .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
            .replace('_', ' ')
            .strip();
        if (spaced.isBlank()) {
            return label;
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** "com/acme/Jobs.java" -> "Jobs"; bare "Jobs" passes through. */
    private static String classNameOf(String file) {
        if (file == null) {
            return "";
        }
        String name = file;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.indexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static String slug(String controller) {
        if (controller == null) {
            return "";
        }
        return controller.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
