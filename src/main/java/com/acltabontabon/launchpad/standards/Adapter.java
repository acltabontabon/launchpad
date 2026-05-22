package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Adapter(
    String id,
    String target,
    String description,
    List<AdapterOutput> outputs
) {}
