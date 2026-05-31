package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * An architectural pattern observed in the codebase. `prevalence` is the
 * fraction of relevant sites that follow the pattern (0.0 to 1.0); a high
 * value indicates a de-facto standard, a low value indicates drift.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DetectedPattern(
    String id,
    String name,
    double prevalence,
    List<Evidence> exemplars,
    Confidence confidence
) {
    public DetectedPattern {
        if (id == null) id = "";
        if (name == null) name = "";
        if (exemplars == null) exemplars = List.of();
        if (confidence == null) confidence = Confidence.INFERRED;
    }
}
