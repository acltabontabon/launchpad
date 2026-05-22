package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Prompt(String id, String title, String template) {}
