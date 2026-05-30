package com.acltabontabon.launchpad.model;

/**
 * Trust level for a synthesized field in the virtualized project model.
 * <p>
 * DETERMINISTIC values come straight from structured analysis (no model
 * involved) and are reproducible. INFERRED values are derived by Launchpad's
 * own heuristics over deterministic facts. LLM_SUGGESTED values were drafted
 * by the local model from structured inputs and should be treated as a
 * proposal, not ground truth.
 */
public enum Confidence {
    DETERMINISTIC,
    INFERRED,
    LLM_SUGGESTED
}
