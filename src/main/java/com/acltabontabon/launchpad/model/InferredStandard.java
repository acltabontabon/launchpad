package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A guardrail Launchpad suggests after observing a high-prevalence pattern that
 * has no matching declared rule. `proposedRule` is a draft directive a tech
 * lead can accept into their versioned standards pack. Always presented as a
 * suggestion, never enforced.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InferredStandard(
    String id,
    String pattern,
    double prevalence,
    List<Evidence> exemplars,
    String proposedRule,
    Confidence confidence
) {
    public InferredStandard {
        if (id == null) id = "";
        if (pattern == null) pattern = "";
        if (exemplars == null) exemplars = List.of();
        if (proposedRule == null) proposedRule = "";
        if (confidence == null) confidence = Confidence.LLM_SUGGESTED;
    }
}
