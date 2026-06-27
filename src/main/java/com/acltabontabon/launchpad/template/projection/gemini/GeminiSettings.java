package com.acltabontabon.launchpad.template.projection.gemini;

/**
 * Renders the minimal {@code .gemini/settings.json} that redirects Gemini CLI's
 * context file to the canonical {@code AGENTS.md}. Deterministic and content-only
 * so two runs of an unchanged project produce identical bytes.
 */
final class GeminiSettings {

    private GeminiSettings() {}

    /** The canonical Launchpad context file Gemini CLI is pointed at. */
    static final String CONTEXT_FILE = "AGENTS.md";

    static String pointingAtAgentsMd() {
        return """
            {
              "context": {
                "fileName": "%s"
              }
            }
            """.formatted(CONTEXT_FILE);
    }
}
