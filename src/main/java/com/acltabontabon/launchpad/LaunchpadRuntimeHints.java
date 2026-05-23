package com.acltabontabon.launchpad;

import com.acltabontabon.launchpad.audit.LlmCheckResult;
import com.acltabontabon.launchpad.mcp.LaunchpadMcpResources;
import com.acltabontabon.launchpad.mcp.LaunchpadMcpTools;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Native-image reflection hints. Covers:
 * <ul>
 *   <li>Classes the Spring AI MCP server discovers via
 *       {@code ReflectionUtils.findMethods(...)} at runtime.</li>
 *   <li>{@link LlmCheckResult}, which Spring AI's structured output converter
 *       reflects on inside {@code LlmChecker}.</li>
 *   <li>The standards record types that {@code StandardsLoader} deserializes
 *       from YAML via Jackson. Without these hints, the canonical record
 *       constructors are stripped and YAML reads fail with "Failed to read".</li>
 * </ul>
 * <p>
 * Prompt-template resources are registered separately via
 * {@code META-INF/native-image/com.acltabontabon/launchpad/resource-config.json}.
 * Spring's {@code hints.resources().registerPattern("prompts/*&#47;*.txt")} was
 * tried first but GraalVM's glob handling failed to bundle the depth-2 files,
 * so we use the legacy regex resource-config which is bulletproof.
 * <p>
 * <b>Known limitation:</b> the reflection hints below are necessary but not
 * sufficient for full native MCP support on Spring AI 2.0.0-M6. With
 * {@code @ConditionalOnProperty} gating the MCP beans, Spring AOT prunes them
 * at build time (the property is unset during AOT, so the condition resolves
 * false). Without the gate, the MCP autoconfig instantiates {@code toolSpecs}
 * eagerly in every mode, which breaks TUI startup. Resolving this cleanly
 * requires either (a) running AOT with the {@code mcp} profile active so the
 * beans survive native compilation, or (b) two separate main classes. Until
 * that is sorted, MCP mode runs on the JVM jar.
 */
public class LaunchpadRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection()
            .registerType(LaunchpadMcpTools.class,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.DECLARED_FIELDS)
            .registerType(LaunchpadMcpResources.class,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.DECLARED_FIELDS)
            .registerType(LlmCheckResult.class,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.DECLARED_FIELDS);

        // Spring AI MCP reflects on DefaultMetaProvider's no-arg constructor at
        // runtime when building tool/resource specifications. Without this hint,
        // native-image strips the constructor and toolSpecs bean instantiation
        // fails with NoSuchMethodException.
        hints.reflection().registerTypeIfPresent(classLoader,
            "org.springframework.ai.mcp.annotation.context.DefaultMetaProvider",
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS);

        // Jackson-YAML reads these records via the canonical constructor and
        // record-component accessors. Several are package-private so we
        // register by FQN. Nested records (Includes, Scope, Check, etc.) need
        // explicit entries too - reachability does not descend into them.
        String[] jacksonRecordTypes = {
            "com.acltabontabon.launchpad.standards.StandardsPackManifest",
            "com.acltabontabon.launchpad.standards.StandardsPackManifest$Includes",
            "com.acltabontabon.launchpad.standards.RulesFile",
            "com.acltabontabon.launchpad.standards.SkillsFile",
            "com.acltabontabon.launchpad.standards.ChecklistsFile",
            "com.acltabontabon.launchpad.standards.PromptsFile",
            "com.acltabontabon.launchpad.standards.AdapterFile",
            "com.acltabontabon.launchpad.standards.Rule",
            "com.acltabontabon.launchpad.standards.Skill",
            "com.acltabontabon.launchpad.standards.Checklist",
            "com.acltabontabon.launchpad.standards.ChecklistItem",
            "com.acltabontabon.launchpad.standards.Prompt",
            "com.acltabontabon.launchpad.standards.Adapter",
            "com.acltabontabon.launchpad.standards.AdapterOutput",
            "com.acltabontabon.launchpad.standards.Scope",
            "com.acltabontabon.launchpad.standards.Check",
            // Project registry persisted to ~/.launchpad/projects.json.
            // Without these the TUI Projects screen is silently empty under native.
            "com.acltabontabon.launchpad.config.ProjectRegistry$Document",
            "com.acltabontabon.launchpad.config.RegisteredProject",
            // Per-project scan persisted to <projectRoot>/.launchpad/scan.json by ScanStore.
            // Read back by MCP tools and any tooling that resumes from cache.
            "com.acltabontabon.launchpad.scanner.ProjectContext",
            "com.acltabontabon.launchpad.scanner.StackProfile",
            "com.acltabontabon.launchpad.scanner.SpringProfile",
            "com.acltabontabon.launchpad.scanner.DatabricksProfile",
            "com.acltabontabon.launchpad.scanner.Dependency",
            "com.acltabontabon.launchpad.scanner.PackageSummary"
        };
        for (String fqn : jacksonRecordTypes) {
            hints.reflection().registerType(TypeReference.of(fqn),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.DECLARED_FIELDS);
        }
    }
}
