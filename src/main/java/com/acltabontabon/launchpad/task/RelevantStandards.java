package com.acltabontabon.launchpad.task;

import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import java.util.List;

/**
 * Filtered standards passed across the task pipeline. Computed once at
 * TASK_INTERVIEW entry by {@link StandardsSelector#selectRelevantStandards} and
 * reused for every interview turn plus the final synthesise call.
 */
public record RelevantStandards(List<Rule> rules, List<Skill> skills, List<Checklist> checklists) {}
