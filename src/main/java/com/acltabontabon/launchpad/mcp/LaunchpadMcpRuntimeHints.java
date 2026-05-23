package com.acltabontabon.launchpad.mcp;

import com.acltabontabon.launchpad.audit.LlmCheckResult;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers reflection metadata for classes the Spring AI MCP server discovers
 * via {@code ReflectionUtils.findMethods(...)} at runtime. Also covers
 * {@link LlmCheckResult}, which Spring AI's structured output converter reflects
 * on when {@code .entity(LlmCheckResult.class)} is called inside {@code LlmChecker}.
 * <p>
 * <b>Known limitation:</b> these hints are necessary but not sufficient for full
 * native MCP support on Spring AI 2.0.0-M6. With {@code @ConditionalOnProperty}
 * gating the MCP beans, Spring AOT prunes them at build time (the property is
 * unset during AOT, so the condition resolves false). Without the gate, the
 * MCP autoconfig instantiates {@code toolSpecs} eagerly in every mode, which
 * breaks TUI startup. Resolving this cleanly requires either (a) running AOT
 * with the {@code mcp} profile active so the beans survive native compilation,
 * or (b) two separate main classes / Spring Boot applications. Until that is
 * sorted, MCP mode runs on the JVM jar.
 */
public class LaunchpadMcpRuntimeHints implements RuntimeHintsRegistrar {

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
    }
}
