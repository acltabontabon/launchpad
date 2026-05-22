package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Rule(
    String id,
    String title,
    String severity,
    @JsonAlias("content") String description,
    String rationale
) {}
