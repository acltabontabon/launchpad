package com.acltabontabon.launchpad.template.synthesis;

import com.acltabontabon.launchpad.scanner.ClassFact;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import java.util.List;
import java.util.Map;

public record SynthesisOutputs(
    String introBody,
    String architectureNarrative,
    List<ClassFact> classFacts,
    List<Endpoint> allEndpoints,
    Map<String, String> endpointNotes,
    String buildProfileBullets
) {}
