package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.springboot.maven.MavenProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildProfilesRendererTest {

    @Test
    void rendersTableWhenProfilesExist() {
        var profiles = List.of(
            new MavenProfile("pgo-instrument", "", List.of("--pgo-instrument")),
            new MavenProfile("pgo-optimized", "JDK 21", List.of("--pgo=profiles/default.iprof"))
        );
        var out = BuildProfilesRenderer.render(profiles);

        assertThat(out).contains("| Profile | Activation | Key flags |");
        assertThat(out).contains("| `pgo-instrument` |");
        assertThat(out).contains("| `pgo-optimized` |");
        assertThat(out).contains("--pgo-instrument");
        assertThat(out).contains("JDK 21");
        // No activation -> opt-in placeholder.
        assertThat(out).contains("opt-in via `-P`");
    }

    @Test
    void emptyListProducesEmptyOutput() {
        assertThat(BuildProfilesRenderer.render(List.of())).isEmpty();
        assertThat(BuildProfilesRenderer.render(null)).isEmpty();
    }

    @Test
    void escapesPipesInActivation() {
        var profile = new MavenProfile("p", "property env=prod|stage", List.of());
        var out = BuildProfilesRenderer.render(List.of(profile));
        assertThat(out).contains("env=prod\\|stage");
    }
}
