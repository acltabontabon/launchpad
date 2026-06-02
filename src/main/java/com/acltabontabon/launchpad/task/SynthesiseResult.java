package com.acltabontabon.launchpad.task;

import java.util.List;

/** Result of a synthesise call: the assembled markdown plus any validator warnings. */
public record SynthesiseResult(String markdown, List<String> warnings) {}
