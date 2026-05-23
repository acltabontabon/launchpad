package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Sub-stack details for a Spring project. Populated by SpringProfileDetector
 * after the main dependency extraction pass. Drives which facets the prompt
 * composer pulls in.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpringProfile(
    boolean web,
    boolean webflux,
    boolean jpa,
    boolean jdbc,
    boolean r2dbc,
    boolean springAi,
    boolean springCloud,
    boolean springSecurity,
    boolean kafka,
    boolean rabbit,
    boolean nativeImage,
    boolean starterLibrary
) {

    public static SpringProfile empty() {
        return new SpringProfile(false, false, false, false, false, false, false, false, false, false, false, false);
    }

    /**
     * Ordered list of facet ids enabled on this profile. The composer reads
     * spring/facets/<id>.txt for each entry. Order is stable so generated
     * prompts diff cleanly across runs.
     * <p>
     * {@code starter-library} is listed first when present so the perspective
     * shift ("you are documenting a library, not an application") frames every
     * later facet section.
     */
    public List<String> facets() {
        var out = new ArrayList<String>();
        if (starterLibrary) out.add("starter-library");
        if (web) out.add("web-mvc");
        if (webflux) out.add("web-webflux");
        if (jpa) out.add("persistence-jpa");
        if (jdbc) out.add("persistence-jdbc");
        if (r2dbc) out.add("persistence-r2dbc");
        if (springAi) out.add("spring-ai");
        if (springCloud) out.add("spring-cloud");
        if (springSecurity) out.add("spring-security");
        if (kafka) out.add("messaging-kafka");
        if (rabbit) out.add("messaging-rabbit");
        if (nativeImage) out.add("graalvm-native");
        return out;
    }
}
