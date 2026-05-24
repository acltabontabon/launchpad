package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.scanner.Endpoint;
import java.util.List;
import java.util.Map;

/**
 * Renders detected HTTP endpoints as a two-column markdown table for the
 * generated `## Endpoints` section. The Endpoint column combines verb and
 * path; the Notes column is filled from {@code notesByEndpoint}, keyed by
 * the string {@code METHOD + " " + path}. Empty when no endpoints exist.
 */
public final class EndpointsTableRenderer {

    private EndpointsTableRenderer() {}

    public static String render(List<Endpoint> endpoints, Map<String, String> notesByEndpoint) {
        if (endpoints == null || endpoints.isEmpty()) return "";
        var notes = notesByEndpoint == null ? Map.<String, String>of() : notesByEndpoint;

        var sb = new StringBuilder();
        sb.append("| Endpoint | Notes |\n");
        sb.append("|----------|-------|\n");
        for (var ep : endpoints) {
            var key = ep.method() + " " + ep.path();
            var note = notes.getOrDefault(key, "");
            sb.append("| `").append(key).append("` | ").append(escapePipes(note)).append(" |\n");
        }
        return sb.toString();
    }

    /** Returns the canonical lookup key the table renderer uses for {@code notesByEndpoint}. */
    public static String key(Endpoint ep) {
        return ep.method() + " " + ep.path();
    }

    private static String escapePipes(String value) {
        return value.replace("|", "\\|");
    }
}
