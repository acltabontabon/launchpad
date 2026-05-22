package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record PromptsFile(int version, List<Prompt> prompts) {}
