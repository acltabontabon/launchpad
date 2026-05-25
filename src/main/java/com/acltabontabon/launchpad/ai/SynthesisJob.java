package com.acltabontabon.launchpad.ai;

import java.util.Set;
import java.util.function.Supplier;

/**
 * One bounded local-AI call that fills a specific section of AGENTS.md.
 * The structure of the document is owned by the template engine; this job
 * provides at most a short body fragment, validated against
 * {@link SynthesisValidator.Shape}. Failed synthesis falls back to the
 * deterministic supplier - it must never fail the surrounding document.
 *
 * @param id              Short identifier for logs / debug ("project-intro",
 *                        "endpoint-notes", ...).
 * @param promptTemplate  The full prompt body. Bounded; should already
 *                        include all needed input. Validated for size before
 *                        the call fires.
 * @param shape           Expected output shape (prose or bullets).
 * @param maxInputChars   Hard cap on prompt size. The job fails closed if the
 *                        template exceeds this.
 * @param maxOutputChars  Hard cap on model output. Anything longer is
 *                        rejected and the fallback is used.
 * @param fallback        Deterministic body the engine uses when synthesis
 *                        is disabled, fails the validator, or times out.
 *                        May return an empty string when the section should
 *                        be omitted entirely.
 * @param allowedTokens   Optional whitelist of identifier tokens the model
 *                        may reference. When non-empty, the validator rejects
 *                        any backticked / path-like reference that does not
 *                        match (case-insensitive substring). Used to catch
 *                        hallucinated package / endpoint / profile names.
 *                        Empty set disables the check.
 */
public record SynthesisJob(
    String id,
    String promptTemplate,
    SynthesisValidator.Shape shape,
    int maxInputChars,
    int maxOutputChars,
    Supplier<String> fallback,
    Set<String> allowedTokens
) {

    public SynthesisJob {
        if (allowedTokens == null) allowedTokens = Set.of();
    }

    /** Convenience constructor for jobs that don't need allowlist validation. */
    public SynthesisJob(String id, String promptTemplate, SynthesisValidator.Shape shape,
                        int maxInputChars, int maxOutputChars, Supplier<String> fallback) {
        this(id, promptTemplate, shape, maxInputChars, maxOutputChars, fallback, Set.of());
    }
}
