package com.acltabontabon.launchpad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Operational facts a new engineer needs to run, build, and observe the
 * project: build profiles, environment variables, ports, health endpoints,
 * build and deploy commands, and observability hooks. All deterministic.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Operations(
    List<String> runProfiles,
    List<String> envVars,
    List<String> ports,
    List<String> healthEndpoints,
    List<String> buildCommands,
    List<String> deployCommands,
    List<String> observabilityHooks
) {
    public Operations {
        if (runProfiles == null) runProfiles = List.of();
        if (envVars == null) envVars = List.of();
        if (ports == null) ports = List.of();
        if (healthEndpoints == null) healthEndpoints = List.of();
        if (buildCommands == null) buildCommands = List.of();
        if (deployCommands == null) deployCommands = List.of();
        if (observabilityHooks == null) observabilityHooks = List.of();
    }

    public static Operations empty() {
        return new Operations(List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of());
    }
}
