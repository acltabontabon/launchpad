package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The standards posture of the project: which declared rules apply, which
 * patterns Launchpad detected, and which guardrails it suggests codifying.
 * `appliedRuleIds` references rule ids from the resolved standards pack.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StandardsProfile(
    List<String> appliedRuleIds,
    List<DetectedPattern> detectedPatterns,
    List<InferredStandard> inferredStandards
) {
    public StandardsProfile {
        if (appliedRuleIds == null) appliedRuleIds = List.of();
        if (detectedPatterns == null) detectedPatterns = List.of();
        if (inferredStandards == null) inferredStandards = List.of();
    }

    public static StandardsProfile empty() {
        return new StandardsProfile(List.of(), List.of(), List.of());
    }
}
