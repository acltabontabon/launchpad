package com.acltabontabon.launchpad.standards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record AdapterFile(int version, Adapter adapter) {}
