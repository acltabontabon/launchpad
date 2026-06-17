package com.acltabontabon.launchpad.standards;

import com.acltabontabon.launchpad.model.ModelIdentity;
import com.acltabontabon.launchpad.standards.index.StandardsSource;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * Canonical content hash for standards records (rules, skills, checklists). The
 * single home for the hash scheme so the sidecar ({@code StandardsIndexAssembler})
 * and the audit pass ({@code AuditService}) fingerprint the same record the same
 * way - a finding's {@code ruleHash} is byte-identical to the sidecar entry's
 * {@code contentHash}, which is what makes drift detection possible.
 *
 * <p>Lives at the standards-domain level rather than under {@code standards/index}
 * because hashing is now a standards identity concern shared by more than the
 * index; placing it in {@code index} would force the {@code audit} package to
 * depend on the index package.
 *
 * <p><b>Canonicalization contract</b> (do not change without bumping consumers,
 * since it would silently alter every hash):
 * <ul>
 *   <li>The hash input is a canonical labeled string of {@code label=value} lines,
 *       never JSON serialization.</li>
 *   <li>Lines are separated by {@code '\n'}.</li>
 *   <li>Null values are normalized to the empty string.</li>
 *   <li>Authored order is preserved for ordered collections (checklist items,
 *       skill steps, output expectations) - they are emitted as indexed lines.
 *       Set-like scope lists are sorted (see {@link #stableJoin}) so trivial
 *       reordering does not churn the hash.</li>
 *   <li>Timestamps - and {@code generatedAt} in particular - are never included.</li>
 *   <li>Source metadata is rendered in one canonical format ({@code source.pack},
 *       {@code source.version}, {@code source.origin}) reused by every method.</li>
 *   <li>The digest is {@link ModelIdentity#sha256}.</li>
 * </ul>
 *
 * <p>The {@link #hashRule} payload is preserved byte-for-byte from the original
 * {@code StandardsIndexAssembler} implementation so existing rule hashes never
 * change.
 *
 * <p>Note that the hash fingerprints the <em>projected record</em>, including its
 * {@code id} and source metadata - it is not only a hash of the prose text. The
 * same normative text under a different {@code id} produces a different hash.
 */
public final class StandardsContentHash {

    private StandardsContentHash() {
    }

    public static String hashRule(Rule rule, @Nullable StandardsSource source) {
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
            .append("checkKind=").append(rule.isAuditable() ? nullToEmpty(rule.check().kind()) : "").append('\n');
        appendSource(sb, source);
        return ModelIdentity.sha256(sb.toString());
    }

    public static String hashSkill(Skill skill, @Nullable StandardsSource source) {
        Scope scope = skill.scope() == null ? Scope.empty() : skill.scope();
        var sb = new StringBuilder()
            .append("id=").append(nullToEmpty(skill.id())).append('\n')
            .append("title=").append(nullToEmpty(skill.title())).append('\n')
            .append("trigger=").append(nullToEmpty(skill.trigger())).append('\n');
        appendOrdered(sb, "steps", skill.steps());
        appendOrdered(sb, "outputExpectations", skill.outputExpectations());
        sb.append("notes=").append(nullToEmpty(skill.notes())).append('\n')
            .append("scope.languages=").append(stableJoin(scope.languages())).append('\n')
            .append("scope.frameworks=").append(stableJoin(scope.frameworks())).append('\n')
            .append("scope.tools=").append(stableJoin(scope.tools())).append('\n')
            .append("scope.tasks=").append(stableJoin(scope.tasks())).append('\n')
            .append("scope.tags=").append(stableJoin(scope.tags())).append('\n');
        appendSource(sb, source);
        return ModelIdentity.sha256(sb.toString());
    }

    public static String hashChecklist(Checklist checklist, @Nullable StandardsSource source) {
        Scope scope = checklist.scope() == null ? Scope.empty() : checklist.scope();
        var sb = new StringBuilder()
            .append("id=").append(nullToEmpty(checklist.id())).append('\n')
            .append("title=").append(nullToEmpty(checklist.title())).append('\n')
            .append("scope.languages=").append(stableJoin(scope.languages())).append('\n')
            .append("scope.frameworks=").append(stableJoin(scope.frameworks())).append('\n')
            .append("scope.tools=").append(stableJoin(scope.tools())).append('\n')
            .append("scope.tasks=").append(stableJoin(scope.tasks())).append('\n')
            .append("scope.tags=").append(stableJoin(scope.tags())).append('\n');
        List<ChecklistItem> items = checklist.items() == null ? List.of() : checklist.items();
        sb.append("items.count=").append(items.size()).append('\n');
        for (int i = 0; i < items.size(); i++) {
            ChecklistItem item = items.get(i);
            sb.append("items.").append(i).append('=')
                .append(nullToEmpty(item.id())).append('|')
                .append(nullToEmpty(item.text())).append('|')
                .append(item.required()).append('\n');
        }
        appendSource(sb, source);
        return ModelIdentity.sha256(sb.toString());
    }

    /** Emits an ordered collection as {@code <label>.count} + indexed lines, preserving authored order. */
    private static void appendOrdered(StringBuilder sb, String label, @Nullable List<String> values) {
        List<String> list = values == null ? List.of() : values;
        sb.append(label).append(".count=").append(list.size()).append('\n');
        for (int i = 0; i < list.size(); i++) {
            sb.append(label).append('.').append(i).append('=').append(nullToEmpty(list.get(i))).append('\n');
        }
    }

    /** Canonical source block. The last line carries no trailing newline. */
    private static void appendSource(StringBuilder sb, @Nullable StandardsSource source) {
        sb.append("source.pack=").append(source == null ? "" : nullToEmpty(source.pack())).append('\n')
            .append("source.version=").append(source == null ? "" : nullToEmpty(source.version())).append('\n')
            .append("source.origin=").append(source == null ? "" : nullToEmpty(source.origin()));
    }

    /** Sorted, comma-joined list so trivial reordering does not churn the hash. */
    private static String stableJoin(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) return "";
        return values.stream().sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
