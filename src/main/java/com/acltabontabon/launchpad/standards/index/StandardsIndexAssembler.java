package com.acltabontabon.launchpad.standards.index;

import com.acltabontabon.launchpad.model.ModelIdentity;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link StandardsIndex} from a resolved rule set. Deterministic and
 * model-free: given the same rules and {@code source}, it produces byte-identical
 * output except for {@code generatedAt}, which is excluded from every per-rule
 * {@link StandardsRuleEntry#contentHash()}.
 *
 * <p>Mirrors {@code ProjectModelAssembler}: stateless, stable ordering, and a
 * content hash stamped from a labeled payload so two runs of an unchanged pack
 * yield identical entries.
 */
@Component
public class StandardsIndexAssembler {

    /** Lower priority value = more important; ties broken by id for stable order. */
    private static final Comparator<Rule> RULE_ORDER =
        Comparator.comparingInt(Rule::priorityValue)
            .thenComparing(r -> r.id() == null ? "" : r.id());

    public StandardsIndex assemble(List<Rule> rules, @Nullable StandardsSource source, String generatedAt) {
        var entries = new ArrayList<StandardsRuleEntry>();
        if (rules != null) {
            rules.stream().sorted(RULE_ORDER).forEach(rule -> entries.add(toEntry(rule, source)));
        }
        return new StandardsIndex(StandardsIndex.SCHEMA_VERSION, generatedAt, source, List.copyOf(entries));
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
            contentHash(rule, source));
    }

    /**
     * Hashes a labeled, newline-delimited {@code key=value} payload rather than a
     * bare concatenation, so field boundaries are unambiguous. {@code generatedAt}
     * is never part of the payload.
     */
    private String contentHash(Rule rule, @Nullable StandardsSource source) {
        Scope scope = rule.scope() == null ? Scope.empty() : rule.scope();
        var sb = new StringBuilder()
            .append("id=").append(nullToEmpty(rule.id())).append('\n')
            .append("title=").append(nullToEmpty(rule.title())).append('\n')
            .append("severity=").append(nullToEmpty(rule.severity())).append('\n')
            .append("scope.languages=").append(stableJoin(scope.languages())).append('\n')
            .append("scope.frameworks=").append(stableJoin(scope.frameworks())).append('\n')
            .append("scope.tools=").append(stableJoin(scope.tools())).append('\n')
            .append("scope.tasks=").append(stableJoin(scope.tasks())).append('\n')
            .append("scope.tags=").append(stableJoin(scope.tags())).append('\n')
            .append("description=").append(nullToEmpty(rule.description())).append('\n')
            .append("rationale=").append(nullToEmpty(rule.rationale())).append('\n')
            .append("auditable=").append(rule.isAuditable()).append('\n')
            .append("checkKind=").append(rule.isAuditable() ? nullToEmpty(rule.check().kind()) : "").append('\n')
            .append("source.pack=").append(source == null ? "" : nullToEmpty(source.pack())).append('\n')
            .append("source.version=").append(source == null ? "" : nullToEmpty(source.version())).append('\n')
            .append("source.origin=").append(source == null ? "" : nullToEmpty(source.origin()));
        return ModelIdentity.sha256(sb.toString());
    }

    /** Sorted, comma-joined list so trivial reordering does not churn the hash. */
    private String stableJoin(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        return values.stream().sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
