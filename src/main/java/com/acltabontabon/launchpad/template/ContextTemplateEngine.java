package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import com.acltabontabon.launchpad.template.companion.CompanionFileBuilder;
import com.acltabontabon.launchpad.template.synthesis.SectionSynthesizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Thin orchestrator: loads standards, resolves the adapter, runs synthesis,
 * builds companion files, then delegates primary-file construction to the
 * per-target {@link PrimaryFileBuilder}.
 */
@Component
public class ContextTemplateEngine {

    private final StandardsLoader standardsLoader;
    private final AdapterResolver adapterResolver;
    private final SectionSynthesizer sectionSynthesizer;
    private final CompanionFileBuilder companionFileBuilder;
    private final Map<ContextTarget, PrimaryFileBuilder> primaryBuilders;

    public ContextTemplateEngine(StandardsLoader standardsLoader,
                                 AdapterResolver adapterResolver,
                                 SectionSynthesizer sectionSynthesizer,
                                 CompanionFileBuilder companionFileBuilder,
                                 List<PrimaryFileBuilder> primaryBuilderList) {
        this.standardsLoader = standardsLoader;
        this.adapterResolver = adapterResolver;
        this.sectionSynthesizer = sectionSynthesizer;
        this.companionFileBuilder = companionFileBuilder;
        this.primaryBuilders = primaryBuilderList.stream()
            .collect(Collectors.toMap(PrimaryFileBuilder::target, b -> b));
    }

    public List<GeneratedFile> buildFiles(
        ProjectContext ctx,
        ContextTarget target,
        String targetSpecificContent
    ) {
        var projectRoot = Path.of(ctx.rootPath());
        var rules = standardsLoader.loadRules(projectRoot);
        var skills = standardsLoader.loadSkills(projectRoot);
        var checklists = standardsLoader.loadChecklists(projectRoot);
        var prompts = standardsLoader.loadPrompts(projectRoot);
        var resolved = adapterResolver.resolve(projectRoot, target);
        var synthesis = sectionSynthesizer.synthesize(ctx);

        var companions = companionFileBuilder.build(
            target, ctx, rules, skills, checklists, prompts, targetSpecificContent);
        var companionPaths = new LinkedHashSet<String>();
        for (var c : companions) companionPaths.add(c.relativePath());

        var plan = AssemblyPlan.forTarget(target);
        var primaryBody = primaryBuilders.get(target).build(ctx, plan, resolved, synthesis, companionPaths);

        var files = new ArrayList<GeneratedFile>();
        files.add(new GeneratedFile(resolved.primaryPath(),
            MergeMarkers.wrap(primaryBody),
            GeneratedFile.FileKind.CONTEXT));
        files.addAll(companions);
        return files;
    }
}
