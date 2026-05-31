package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.model.InferredStandard;
import com.acltabontabon.launchpad.model.Operations;
import com.acltabontabon.launchpad.model.Risk;
import com.acltabontabon.launchpad.model.VirtualProjectContext;
import com.acltabontabon.launchpad.model.Workflow;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.template.rendering.PointerBlocks;
import com.acltabontabon.launchpad.template.synthesis.SynthesisOutputs;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic primary-file builder for the vendor-neutral AGENTS.md output.
 * Java owns every heading, table, and section; synthesis fills bounded body
 * fragments with deterministic fallbacks per job. The model can never invent
 * the document structure.
 */
@Component
class AgentsPrimaryFileBuilder implements PrimaryFileBuilder {

    @Override
    public String build(ProjectContext ctx, VirtualProjectContext model, AssemblyPlan plan,
                        AdapterResolver.ResolvedAdapter resolved,
                        SynthesisOutputs synthesis, Set<String> companionPaths) {
        var sb = new StringBuilder();
        // H1 is the project name. The filename is metadata - the body reads
        // about the project, not about itself.
        var title = ctx.name() == null || ctx.name().isBlank() ? "Project" : ctx.name();
        sb.append("# ").append(title).append("\n\n");

        for (var section : plan.sections()) {
            switch (section) {
                case INTRO -> sb.append("## What this project is\n\n")
                    .append(synthesis.introBody()).append("\n\n");

                case COMMANDS -> {
                    // CommandsRenderer already emits its own "## Commands" heading;
                    // skip entirely when no build tool was detected.
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
                        // Fallback for projects whose JVM sources we couldn't classify
                        // (no top-level declaration matched, or all files filtered out):
                        // emit the simpler `## Project map` tree so the section is not lost.
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

                case WORKFLOWS -> appendWorkflows(sb, model);

                case OPERATIONS -> appendOperations(sb, model);

                case RISKS -> appendRisks(sb, model);

                case INFERRED_STANDARDS -> appendInferredStandards(sb, model);

                case COMPANION_POINTERS -> {
                    var block = PointerBlocks.renderGeneratedContextBlock(companionPaths);
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

    /**
     * Projects the discovered workflows from the virtualized model - what the
     * service actually does, grouped by resource. Each workflow lists its
     * trigger and the routes that make it up. Rendered only when workflows were
     * discovered, so naked projects stay clean.
     */
    private void appendWorkflows(StringBuilder sb, VirtualProjectContext model) {
        if (model == null || model.workflows() == null || model.workflows().isEmpty()) return;

        sb.append("## Workflows\n\n");
        for (Workflow workflow : model.workflows()) {
            sb.append("- **").append(workflow.name()).append("** (")
                .append(workflow.type().name().toLowerCase().replace('_', ' '))
                .append(")");
            if (workflow.trigger() != null && !workflow.trigger().isBlank()) {
                sb.append(" - trigger: `").append(workflow.trigger()).append("`");
            }
            sb.append("\n");
            for (var step : workflow.steps()) {
                sb.append("  - `").append(step).append("`\n");
            }
            appendTouches(sb, "touches", workflow.touchedSystems());
            appendTouches(sb, "external", workflow.externalCalls());
            appendTouches(sb, "data", workflow.dataEffects());
        }
        sb.append("\n");
    }

    /** Renders one correlation line (e.g. "touches: `OrderService`, `Pricing`") when non-empty. */
    private void appendTouches(StringBuilder sb, String label, java.util.List<String> values) {
        if (values == null || values.isEmpty()) return;
        sb.append("  - ").append(label).append(": ");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("`").append(values.get(i)).append("`");
        }
        sb.append("\n");
    }

    /**
     * Projects inferred risks from the virtualized model - drift and concerns
     * Launchpad surfaced from observed consistency (e.g. layering violations).
     * Rendered only when risks exist, so clean projects stay clean.
     */
    private void appendRisks(StringBuilder sb, VirtualProjectContext model) {
        if (model == null || model.risks() == null || model.risks().isEmpty()) return;

        sb.append("## Risks\n\n");
        for (Risk risk : model.risks()) {
            sb.append("- **").append(risk.severity().name().toLowerCase()).append("** [")
                .append(risk.category()).append("] ").append(risk.description());
            if (risk.suggestedMitigation() != null && !risk.suggestedMitigation().isBlank()) {
                sb.append(" _").append(risk.suggestedMitigation()).append("_");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    /**
     * Projects guardrails Launchpad suggests codifying - conventions the code
     * already follows that have no declared rule yet. Clearly labelled as
     * suggestions so they are never mistaken for enforced standards. Rendered
     * only when suggestions exist.
     */
    private void appendInferredStandards(StringBuilder sb, VirtualProjectContext model) {
        if (model == null || model.standards() == null
            || model.standards().inferredStandards().isEmpty()) {
            return;
        }
        sb.append("## Inferred standards (suggested, not enforced)\n\n");
        for (InferredStandard s : model.standards().inferredStandards()) {
            sb.append("- ").append(s.proposedRule()).append("\n");
        }
        sb.append("\n");
    }

    /**
     * Projects operational facts from the virtualized model: build profiles and
     * health endpoints (Spring Actuator routes). Build/run commands live under
     * {@code ## Commands}, so they are not repeated here. Rendered only when
     * there is something to show, so naked projects stay clean.
     */
    private void appendOperations(StringBuilder sb, VirtualProjectContext model) {
        if (model == null || model.operations() == null) return;
        Operations ops = model.operations();
        boolean hasProfiles = !ops.runProfiles().isEmpty();
        boolean hasHealth = !ops.healthEndpoints().isEmpty();
        if (!hasProfiles && !hasHealth) return;

        sb.append("## Operations\n\n");
        if (hasProfiles) {
            sb.append("Build profiles:\n");
            for (var profile : ops.runProfiles()) {
                sb.append("- `").append(profile).append("`\n");
            }
            sb.append("\n");
        }
        if (hasHealth) {
            sb.append("Health endpoints:\n");
            for (var endpoint : ops.healthEndpoints()) {
                sb.append("- `").append(endpoint).append("`\n");
            }
            sb.append("\n");
        }
    }
}
