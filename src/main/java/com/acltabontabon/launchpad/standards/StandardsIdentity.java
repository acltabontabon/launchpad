package com.acltabontabon.launchpad.standards;

import com.acltabontabon.launchpad.model.ModelIdentity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Guarantees every standards record carries a stable, non-blank, unique {@code id}.
 *
 * <p>Authored ids are the real stability boundary and are always preferred -
 * they are taken verbatim. A blank id is filled deterministically from the
 * record's title (or a checklist item's text) via {@link ModelIdentity#slug}.
 * Because generated ids are title/text-derived, changing the title or item text
 * can change the generated id; authored ids do not have this caveat.
 *
 * <p>Generated ids are made unique within their scope (rules within the rule
 * collection, skills within skills, checklists within checklists, items within
 * their containing checklist). On collision a numeric suffix is appended in
 * resolved authored order ({@code slug}, {@code slug-2}, {@code slug-3}, ...).
 * Uniqueness is computed by walking the authored {@code List} in order and
 * tracking taken ids in an insertion-ordered set, so it never depends on
 * hash-map iteration order and repeated loads of identical input yield identical
 * ids.
 */
public final class StandardsIdentity {

    private StandardsIdentity() {
    }

    public static List<Rule> normalizeRules(List<Rule> rules) {
        return assignIds(rules, Rule::id, r -> r.title(), "rule",
            (r, id) -> new Rule(id, r.title(), r.severity(), r.description(),
                r.rationale(), r.scope(), r.priority(), r.check()));
    }

    public static List<Skill> normalizeSkills(List<Skill> skills) {
        return assignIds(skills, Skill::id, s -> s.title(), "skill",
            (s, id) -> new Skill(id, s.title(), s.trigger(), s.steps(),
                s.outputExpectations(), s.notes(), s.scope()));
    }

    public static List<Checklist> normalizeChecklists(List<Checklist> checklists) {
        // Normalize items within each checklist first (scope = the containing checklist),
        // then normalize checklist ids across the collection.
        List<Checklist> withItems = new ArrayList<>();
        for (Checklist c : nullToEmpty(checklists)) {
            withItems.add(new Checklist(c.id(), c.title(), normalizeItems(c.items()), c.scope()));
        }
        return assignIds(withItems, Checklist::id, c -> c.title(), "checklist",
            (c, id) -> new Checklist(id, c.title(), c.items(), c.scope()));
    }

    private static List<ChecklistItem> normalizeItems(List<ChecklistItem> items) {
        return assignIds(items, ChecklistItem::id, ChecklistItem::text, "item",
            (item, id) -> new ChecklistItem(id, item.text(), item.required()));
    }

    /**
     * Fills blank ids and de-duplicates generated ones. Authored (non-blank) ids
     * are reserved up front and kept as-is; generated ids skip past every taken id.
     */
    private static <T> List<T> assignIds(
        List<T> items,
        Function<T, String> idOf,
        Function<T, String> slugSourceOf,
        String emptyBase,
        BiFunction<T, String, T> withId
    ) {
        List<T> source = nullToEmpty(items);
        Set<String> taken = new LinkedHashSet<>();
        for (T item : source) {
            String authored = idOf.apply(item);
            if (isPresent(authored)) taken.add(authored);
        }

        List<T> result = new ArrayList<>(source.size());
        for (T item : source) {
            String authored = idOf.apply(item);
            if (isPresent(authored)) {
                result.add(item);
                continue;
            }
            String base = baseSlug(slugSourceOf.apply(item), emptyBase);
            String candidate = base;
            int suffix = 2;
            while (taken.contains(candidate)) {
                candidate = base + "-" + suffix++;
            }
            taken.add(candidate);
            result.add(withId.apply(item, candidate));
        }
        return List.copyOf(result);
    }

    private static String baseSlug(String value, String emptyBase) {
        String slug = ModelIdentity.slug(value);
        return slug.isEmpty() ? emptyBase : slug;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
