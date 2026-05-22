package com.acltabontabon.launchpad.ai;

import java.util.List;

/**
 * Result of one streamed AI generation, carrying the raw content plus any
 * validation warnings (missing sections, suspicious file references, etc.).
 * Warnings surface in the Review screen so users see degraded output
 * instead of silently accepting it.
 */
public record GeneratedOutput(String content, List<String> warnings, boolean retried) {

    public static GeneratedOutput ok(String content, boolean retried) {
        return new GeneratedOutput(content, List.of(), retried);
    }

    public static GeneratedOutput withWarnings(String content, List<String> warnings, boolean retried) {
        return new GeneratedOutput(content, warnings, retried);
    }
}
