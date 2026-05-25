package com.acltabontabon.launchpad.template.projection;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Skill;
import com.acltabontabon.launchpad.template.GeneratedFile;
import java.util.List;

/**
 * Translates Launchpad's canonical engineering model (rules, skills,
 * checklists) into agent-native discovery / consumption files.
 *
 * <p>An {@code AgentProjection} is the consumer of the canonical model -
 * never its owner. Implementations must not store, redefine, or
 * authoritatively render any engineering intent; they only project the
 * already-canonical {@link Rule} / {@link Skill} / {@link Checklist}
 * records into vendor-shaped {@link GeneratedFile} entries.
 *
 * <p>The core engine resolves enabled projection ids and invokes matching
 * beans. It does not know the file formats those beans produce.
 *
 * <p>New AI tool integrations must be implemented as additional
 * {@code AgentProjection} beans. Do not introduce agent-specific branches
 * into the engine, the companion builder, the canonical renderers, or
 * the canonical model.
 */
public interface AgentProjection {

    /**
     * Stable identifier used to enable this projection in
     * {@code standards-pack.yml}'s {@code projections:} list (e.g.
     * {@code "claude"}, {@code "cursor"}, {@code "copilot"}).
     */
    String id();

    /**
     * Project the canonical model into agent-native files. Implementations
     * may return an empty list when nothing applies.
     */
    List<GeneratedFile> project(
        ProjectContext ctx,
        List<Rule> rules,
        List<Skill> skills,
        List<Checklist> checklists
    );
}
