package com.acltabontabon.launchpad.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Structured-output schema for {@link LlmChecker}. The local model is asked a
 * yes/no question per file and must return JSON matching this shape; Spring AI's
 * {@code BeanOutputConverter} (via {@code .entity(...)}) handles the schema hint
 * and parsing. {@code line} is best-effort: when the model can pinpoint the
 * offending line it returns it, otherwise {@code 0} or {@code null}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmCheckResult(
    boolean violated,
    String reason,
    Integer line
) {}
