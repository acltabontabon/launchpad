package com.acltabontabon.launchpad.springboot.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single HTTP route detected from source. Populated by EndpointExtractor
 * during the scan; surfaced to the LLM through {@link ProjectContext#toPromptString}
 * so it can produce a faithful `## Endpoints` table.
 *
 * @param method     HTTP verb in uppercase (GET, POST, ...). "REQUEST" when
 *                   the source uses `@RequestMapping` without a specific verb.
 * @param path       Full request path, base + method-level segment concatenated.
 *                   Always starts with `/`. Empty path defaults to `/`.
 * @param handler    Short handler reference shown back to the model -
 *                   `<simple-class-name>.<method-name>` (e.g. `LoanDecisionController.decide`).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Endpoint(String method, String path, String handler) {
}
