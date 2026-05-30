package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Synthesized architectural overview. `style` is the detected high-level shape
 * (e.g. "layered", "hexagonal"), `layers` and `modules` are deterministic
 * structural facts, `dependencyMap` links a module to the modules it depends
 * on, and `narrative` is an optional model-written summary. `confidence`
 * reflects the weakest input used and `evidence` backs the synthesized claims.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Architecture(
    String style,
    List<String> layers,
    List<String> modules,
    Map<String, List<String>> dependencyMap,
    String narrative,
    List<String> diagramRefs,
    Confidence confidence,
    List<Evidence> evidence
) {
    public Architecture {
        if (style == null) style = "";
        if (layers == null) layers = List.of();
        if (modules == null) modules = List.of();
        if (dependencyMap == null) dependencyMap = Map.of();
        if (narrative == null) narrative = "";
        if (diagramRefs == null) diagramRefs = List.of();
        if (confidence == null) confidence = Confidence.DETERMINISTIC;
        if (evidence == null) evidence = List.of();
    }

    public static Architecture empty() {
        return new Architecture("", List.of(), List.of(), Map.of(), "", List.of(),
            Confidence.DETERMINISTIC, List.of());
    }
}
