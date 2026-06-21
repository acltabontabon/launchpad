package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.model.VirtualProjectContext;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import com.acltabontabon.launchpad.template.companion.CompanionFileBuilder;
import com.acltabontabon.launchpad.template.projection.AgentProjection;
import com.acltabontabon.launchpad.template.synthesis.SectionSynthesizer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Projector for the virtualized {@link VirtualProjectContext}: loads canonical
 * standards, resolves the adapter, runs synthesis, builds canonical companion
 * files, delegates primary-file construction to the {@link PrimaryFileBuilder}
 * (passing the model so its synthesized sections can be projected), and finally
 * invokes every enabled {@link AgentProjection} bean to emit agent-native
 * discovery files.
 *
 * <p>The engine does not know vendor file formats. It only resolves enabled
 * projection ids and dispatches to matching beans.
 */
@Component
public class ContextTemplateEngine {

    private final StandardsLoader standardsLoader;
    private final AdapterResolver adapterResolver;
    private final SectionSynthesizer sectionSynthesizer;
    private final CompanionFileBuilder companionFileBuilder;
    private final PrimaryFileBuilder primaryBuilder;
    private final List<AgentProjection> projections;

    public ContextTemplateEngine(StandardsLoader standardsLoader,
                                 AdapterResolver adapterResolver,
                                 SectionSynthesizer sectionSynthesizer,
                                 CompanionFileBuilder companionFileBuilder,
                                 PrimaryFileBuilder primaryBuilder,
                                 List<AgentProjection> projections) {
        this.standardsLoader = standardsLoader;
        this.adapterResolver = adapterResolver;
        this.sectionSynthesizer = sectionSynthesizer;
        this.companionFileBuilder = companionFileBuilder;
        this.primaryBuilder = primaryBuilder;
        this.projections = projections;
    }

    /**
     * Convenience overload that stamps the current instant and reports
     * {@code deterministic-only} provenance. Used by tests and any caller without
     * an active-model handle.
     */
    public List<GeneratedFile> buildFiles(ProjectContext ctx, VirtualProjectContext model) {
        return buildFiles(ctx, model, "", Instant.now().toString());
    }

    /**
     * @param activeModel configured model name; stamped into provenance when AI
     *                    synthesis is enabled, otherwise {@code deterministic-only}.
     * @param generatedAt ISO-8601 timestamp stamped into provenance (passed in so
     *                    tests can assert a fixed value).
     */
    public List<GeneratedFile> buildFiles(ProjectContext ctx, VirtualProjectContext model,
                                          String activeModel, String generatedAt) {
        var projectRoot = Path.of(ctx.rootPath());
        var rules = standardsLoader.loadRules(projectRoot);
        var skills = standardsLoader.loadSkills(projectRoot);
        var checklists = standardsLoader.loadChecklists(projectRoot);
        var resolved = adapterResolver.resolve(projectRoot);
        var synthesis = sectionSynthesizer.synthesize(ctx);

        var source = standardsLoader.describeRulesSource(projectRoot).orElse(null);
        var aiModel = sectionSynthesizer.isAiEnabled() && activeModel != null && !activeModel.isBlank()
            ? activeModel
            : ProvenanceHeader.DETERMINISTIC_ONLY;
        var provenance = ProvenanceHeader.of(generatedAt, source, aiModel).render();

        var companions = companionFileBuilder.build(ctx, rules, skills, checklists).stream()
            .map(c -> new GeneratedFile(c.relativePath(), provenance + c.content(), c.kind()))
            .toList();
        var companionPaths = new LinkedHashSet<String>();
        for (var c : companions) companionPaths.add(c.relativePath());

        var plan = AssemblyPlan.standard();
        var primaryBody = primaryBuilder.build(ctx, model, plan, resolved, synthesis, companionPaths);

        var files = new ArrayList<GeneratedFile>();
        // Provenance sits inside the managed block so a re-run's merge refreshes it
        // in place; outside the markers it would go stale on every MERGE.
        files.add(new GeneratedFile(resolved.primaryPath(),
            MergeMarkers.wrap(provenance + primaryBody),
            GeneratedFile.FileKind.CONTEXT));
        files.addAll(companions);

        Set<String> enabledIds = standardsLoader.loadProjectionIds(projectRoot);
        for (var projection : projections) {
            if (enabledIds.contains(projection.id())) {
                files.addAll(projection.project(ctx, rules, skills, checklists));
            }
        }

        return files;
    }
}
