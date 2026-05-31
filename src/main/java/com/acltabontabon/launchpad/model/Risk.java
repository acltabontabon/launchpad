package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A synthesized concern about the project. `category` groups the risk (e.g.
 * "security", "testing", "drift"), `affectedSystems` holds
 * {@link SystemComponent} ids, and `evidence` grounds the claim so a reader
 * can verify it rather than taking it on faith.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Risk(
    String id,
    String category,
    RiskSeverity severity,
    String description,
    List<Evidence> evidence,
    List<String> affectedSystems,
    String suggestedMitigation,
    Confidence confidence
) {
    public Risk {
        if (id == null) id = "";
        if (category == null) category = "";
        if (severity == null) severity = RiskSeverity.LOW;
        if (description == null) description = "";
        if (evidence == null) evidence = List.of();
        if (affectedSystems == null) affectedSystems = List.of();
        if (suggestedMitigation == null) suggestedMitigation = "";
        if (confidence == null) confidence = Confidence.INFERRED;
    }
}
