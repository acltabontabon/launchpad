package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdapterOutput(
    String path,
    String kind,
    List<String> includes,
    Map<String, String> frontmatter
) {}
