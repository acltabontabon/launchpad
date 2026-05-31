package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A discovered business or operational workflow. The skeleton (`trigger`,
 * `steps`, `touchedSystems`, `externalCalls`, `dataEffects`) is derived
 * deterministically from a correlation graph; `name` and `purpose` may be
 * labelled by the local model. `touchedSystems` holds {@link SystemComponent}
 * ids so the flow cross-references the system map.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Workflow(
    String id,
    String name,
    String purpose,
    WorkflowType type,
    String trigger,
    List<String> steps,
    List<String> touchedSystems,
    List<String> externalCalls,
    List<String> dataEffects,
    Confidence confidence,
    List<Evidence> evidence
) {
    public Workflow {
        if (id == null) id = "";
        if (name == null) name = "";
        if (purpose == null) purpose = "";
        if (type == null) type = WorkflowType.INTERNAL;
        if (trigger == null) trigger = "";
        if (steps == null) steps = List.of();
        if (touchedSystems == null) touchedSystems = List.of();
        if (externalCalls == null) externalCalls = List.of();
        if (dataEffects == null) dataEffects = List.of();
        if (confidence == null) confidence = Confidence.DETERMINISTIC;
        if (evidence == null) evidence = List.of();
    }
}
