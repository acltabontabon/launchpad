package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EndpointsTableRendererTest {

    @Test
    void rendersTwoColumnTableWithMatchedNotes() {
        var endpoints = List.of(
            new Endpoint("POST", "/loan-decision", "LoanDecisionController.decide"),
            new Endpoint("GET", "/hello", "HelloController.hello"));
        var notes = Map.of(
            "POST /loan-decision", "Main workload",
            "GET /hello", "Sanity check");

        var out = EndpointsTableRenderer.render(endpoints, notes);

        assertThat(out).startsWith("| Endpoint | Notes |\n|----------|-------|\n");
        assertThat(out).contains("| `POST /loan-decision` | Main workload |");
        assertThat(out).contains("| `GET /hello` | Sanity check |");
    }

    @Test
    void emptyNotesCellWhenLookupMisses() {
        var endpoints = List.of(new Endpoint("GET", "/actuator/health", "actuator"));
        var out = EndpointsTableRenderer.render(endpoints, Map.of());
        assertThat(out).contains("| `GET /actuator/health` |  |");
    }

    @Test
    void escapesPipesInNotes() {
        var endpoints = List.of(new Endpoint("GET", "/x", "h"));
        var notes = Map.of("GET /x", "a|b");
        var out = EndpointsTableRenderer.render(endpoints, notes);
        assertThat(out).contains("a\\|b");
    }

    @Test
    void returnsEmptyForEmptyOrNullInput() {
        assertThat(EndpointsTableRenderer.render(List.of(), Map.of())).isEmpty();
        assertThat(EndpointsTableRenderer.render(null, Map.of())).isEmpty();
    }

    @Test
    void keyHelperReturnsMethodSpacePath() {
        var ep = new Endpoint("GET", "/hello", "");
        assertThat(EndpointsTableRenderer.key(ep)).isEqualTo("GET /hello");
    }
}
