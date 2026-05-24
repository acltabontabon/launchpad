package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.template.rendering.PointerBlocks;
import com.acltabontabon.launchpad.template.synthesis.SynthesisOutputs;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic primary-file builder for the Cursor target.
 * Same section sequence as {@link ClaudePrimaryFileBuilder} so both targets
 * share one mental model; only the title preamble and Standards pointer
 * paths differ. Prepends adapter frontmatter when present.
 */
@Component
class CursorPrimaryFileBuilder implements PrimaryFileBuilder {

    @Override
    public ContextTarget target() {
        return ContextTarget.CURSOR;
    }

    @Override
    public String build(ProjectContext ctx, AssemblyPlan plan,
                        AdapterResolver.ResolvedAdapter resolved,
                        SynthesisOutputs synthesis, Set<String> companionPaths) {
        var sb = new StringBuilder();

        // Adapter-driven frontmatter (Cursor convention). When no adapter
        // resolves, omit the block entirely - the legacy `.cursorrules` had
        // no frontmatter either.
        resolved.primaryOutput().ifPresent(out -> {
            var fm = out.frontmatter();
            if (fm != null && !fm.isEmpty()) {
                sb.append("---\n");
                fm.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
                sb.append("---\n\n");
            }
        });

        sb.append("# ").append(ctx.name()).append("\n\n");

        for (var section : plan.sections()) {
            switch (section) {
                case INTRO -> sb.append("## What this project is\n\n")
                    .append(synthesis.introBody()).append("\n\n");

                case COMMANDS -> {
                    if (CommandsRenderer.hasCommands(ctx.stack())) {
                        sb.append(CommandsRenderer.render(ctx.stack()));
                    }
                }

                case ARCHITECTURE -> {
                    if (!synthesis.classFacts().isEmpty()) {
                        sb.append("## Architecture\n\n");
                        if (!synthesis.architectureNarrative().isBlank())
                            sb.append(synthesis.architectureNarrative()).append("\n\n");
                        sb.append(ArchitectureTreeRenderer.render(synthesis.classFacts())).append("\n");
                    } else if (!ctx.packageSummaries().isEmpty()) {
                        sb.append("## Project map\n\n");
                        sb.append(FileTreeRenderer.render(ctx.packageSummaries())).append("\n");
                    }
                }

                case ENDPOINTS -> {
                    if (!synthesis.allEndpoints().isEmpty()) {
                        sb.append("## Endpoints\n\n");
                        sb.append(EndpointsTableRenderer.render(
                            synthesis.allEndpoints(), synthesis.endpointNotes())).append("\n");
                    }
                }

                case BUILD_PROFILES -> {
                    if (!ctx.mavenProfiles().isEmpty()) {
                        sb.append("## Build profiles\n\n");
                        sb.append(BuildProfilesRenderer.render(ctx.mavenProfiles())).append("\n");
                        if (!synthesis.buildProfileBullets().isEmpty())
                            sb.append(synthesis.buildProfileBullets()).append("\n");
                        sb.append("\n");
                    }
                }

                case COMPANION_POINTERS -> {
                    var block = PointerBlocks.renderCursorStandardsBlock(companionPaths);
                    if (!block.isEmpty()) sb.append(block);
                }

                case BOUNDARIES -> {
                    sb.append("## Boundaries for AI agents\n\n");
                    sb.append("- Do not rewrite generated context unless asked.\n");
                    sb.append("- Prefer existing commands from this file.\n");
                    sb.append("- Treat scanner output as evidence, not absolute truth.\n");
                    sb.append("- Keep changes scoped to the requested task.\n");
                }
            }
        }

        return sb.toString();
    }
}
