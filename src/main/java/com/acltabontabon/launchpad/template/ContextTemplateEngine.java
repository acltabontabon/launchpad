package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import com.acltabontabon.launchpad.template.companion.CompanionFileBuilder;
import com.acltabontabon.launchpad.template.synthesis.SectionSynthesizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Thin orchestrator: loads standards, resolves the adapter, runs synthesis,
 * builds companion files, then delegates primary-file construction to the
 * {@link PrimaryFileBuilder}.
 */
@Component
public class ContextTemplateEngine {

    private final StandardsLoader standardsLoader;
    private final AdapterResolver adapterResolver;
    private final SectionSynthesizer sectionSynthesizer;
    private final CompanionFileBuilder companionFileBuilder;
    private final PrimaryFileBuilder primaryBuilder;

    public ContextTemplateEngine(StandardsLoader standardsLoader,
                                 AdapterResolver adapterResolver,
                                 SectionSynthesizer sectionSynthesizer,
                                 CompanionFileBuilder companionFileBuilder,
                                 PrimaryFileBuilder primaryBuilder) {
        this.standardsLoader = standardsLoader;
        this.adapterResolver = adapterResolver;
        this.sectionSynthesizer = sectionSynthesizer;
        this.companionFileBuilder = companionFileBuilder;
        this.primaryBuilder = primaryBuilder;
    }

    public List<GeneratedFile> buildFiles(ProjectContext ctx) {
        var projectRoot = Path.of(ctx.rootPath());
        var rules = standardsLoader.loadRules(projectRoot);
        var skills = standardsLoader.loadSkills(projectRoot);
        var checklists = standardsLoader.loadChecklists(projectRoot);
        var resolved = adapterResolver.resolve(projectRoot);
        var synthesis = sectionSynthesizer.synthesize(ctx);

        var companions = companionFileBuilder.build(ctx, rules, skills, checklists);
        var companionPaths = new LinkedHashSet<String>();
        for (var c : companions) companionPaths.add(c.relativePath());

        var plan = AssemblyPlan.standard();
        var primaryBody = primaryBuilder.build(ctx, plan, resolved, synthesis, companionPaths);

        var files = new ArrayList<GeneratedFile>();
        files.add(new GeneratedFile(resolved.primaryPath(),
            MergeMarkers.wrap(primaryBody),
            GeneratedFile.FileKind.CONTEXT));
        files.addAll(companions);
        return files;
    }
}
