package com.acltabontabon.launchpad.standards;

import java.util.List;

public record Skill(String id, String trigger, List<String> steps, String notes) {}
