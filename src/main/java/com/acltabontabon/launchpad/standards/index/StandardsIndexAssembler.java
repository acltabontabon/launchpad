package com.acltabontabon.launchpad.standards.index;

import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ResolvedStandards;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.standards.StandardsContentHash;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link StandardsIndex} from a resolved pack. Deterministic and
 * model-free: given the same {@link ResolvedStandards} and {@code source}, it
 * produces byte-identical output except for {@code generatedAt}, which is excluded
 * from every record's {@code contentHash}.
 *
 * <p>Mirrors {@code ProjectModelAssembler}: stateless, stable ordering, and a
 * content hash stamped from a labeled payload (via {@link StandardsContentHash})
 * so two runs of an unchanged pack yield identical entries. Hashing is shared with
 * the audit pass, so a finding's {@code ruleHash} equals the matching rule entry's
 * {@code contentHash}.
 */
@Component
public class StandardsIndexAssembler {

    /** Lower priority value = more important; ties broken by id for stable order. */
    private static final Comparator<Rule> RULE_ORDER =
        Comparator.comparingInt(Rule::priorityValue)
            .thenComparing(r -> r.id() == null ? "" : r.id());

    private static final Comparator<Skill> SKILL_ORDER =
        Comparator.comparing(s -> s.id() == null ? "" : s.id());

    private static final Comparator<Checklist> CHECKLIST_ORDER =
        Comparator.comparing(c -> c.id() == null ? "" : c.id());

    public StandardsIndex assemble(ResolvedStandards resolved, String generatedAt) {
        StandardsSource source = resolved.source();

        var ruleEntries = new ArrayList<StandardsRuleEntry>();
        resolved.rules().stream().sorted(RULE_ORDER)
            .forEach(rule -> ruleEntries.add(toEntry(rule, source)));

        var skillEntries = new ArrayList<StandardsSkillEntry>();
        resolved.skills().stream().sorted(SKILL_ORDER)
            .forEach(skill -> skillEntries.add(toEntry(skill, source)));

        var checklistEntries = new ArrayList<StandardsChecklistEntry>();
        resolved.checklists().stream().sorted(CHECKLIST_ORDER)
            .forEach(checklist -> checklistEntries.add(toEntry(checklist, source)));

        return new StandardsIndex(
            StandardsIndex.SCHEMA_VERSION,
            generatedAt,
            source,
            List.copyOf(ruleEntries),
            List.copyOf(skillEntries),
            List.copyOf(checklistEntries));
    }

    private StandardsRuleEntry toEntry(Rule rule, @Nullable StandardsSource source) {
        String checkKind = rule.isAuditable() ? rule.check().kind() : null;
        return new StandardsRuleEntry(
            rule.id(),
            rule.title(),
            rule.severity(),
            rule.scope(),
            rule.description(),
            rule.rationale(),
            source,
            rule.isAuditable(),
            checkKind,
            rule.priorityValue(),
            StandardsContentHash.hashRule(rule, source));
    }

    private StandardsSkillEntry toEntry(Skill skill, @Nullable StandardsSource source) {
        return new StandardsSkillEntry(
            skill.id(),
            skill.title(),
            skill.trigger(),
            skill.steps(),
            skill.outputExpectations(),
            skill.notes(),
            skill.scope(),
            source,
            StandardsContentHash.hashSkill(skill, source));
    }

    private StandardsChecklistEntry toEntry(Checklist checklist, @Nullable StandardsSource source) {
        return new StandardsChecklistEntry(
            checklist.id(),
            checklist.title(),
            checklist.scope(),
            checklist.items(),
            source,
            StandardsContentHash.hashChecklist(checklist, source));
    }
}
