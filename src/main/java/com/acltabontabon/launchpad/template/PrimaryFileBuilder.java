package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.model.VirtualProjectContext;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.template.synthesis.SynthesisOutputs;
import java.util.Set;

public interface PrimaryFileBuilder {

    String build(ProjectContext ctx, VirtualProjectContext model, AssemblyPlan plan,
                 AdapterResolver.ResolvedAdapter resolved,
                 SynthesisOutputs synthesis, Set<String> companionPaths);
}
