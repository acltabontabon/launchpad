package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.ai.ContextGeneratorService;
import com.acltabontabon.launchpad.ai.FacetPromptComposer;
import com.acltabontabon.launchpad.ai.PromptSelector;
import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.springboot.scanner.ProjectScanner;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import com.acltabontabon.launchpad.template.AdapterResolver;
import com.acltabontabon.launchpad.template.AgentsPrimaryFileBuilder;
import com.acltabontabon.launchpad.template.synthesis.SectionSynthesizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * End-to-end generation verification. Not part of the default test suite -
 * class name doesn't match `*Test.java`. Run explicitly:
 *
 *   ./mvnw test -Dtest=ContextGenerationVerification -DLAUNCHPAD_VERIFY_PROJECT=/path/to/project
 *
 * Requires:
 *   - LAUNCHPAD_VERIFY_PROJECT env var pointing at a Spring Boot Maven project
 *   - Ollama running at http://localhost:11434
 *   - the configured model is pulled
 *
 * Prints the generated AGENTS.md to stdout and writes it to
 * `target/verification-agents.md` for inspection.
 */
class ContextGenerationVerification {

    private static final String TARGET_PROJECT = System.getenv().getOrDefault(
        "LAUNCHPAD_VERIFY_PROJECT", System.getProperty("user.home") + "/spring-boot-demo");
    private static final String OLLAMA_URL = "http://localhost:11434";
    private static final String OLLAMA_MODEL = "qwen2.5-coder:7b-instruct";

    @Test
    void verifyAgentsMdGeneration() throws Exception {
        var projectRoot = Path.of(TARGET_PROJECT);
        if (!Files.isDirectory(projectRoot)) {
            System.err.println("[skip] target project not found: " + TARGET_PROJECT);
            return;
        }

        // 1) Scan
        var scanner = ProjectScanner.forTesting();
        var ctx = scanner.scan(TARGET_PROJECT, msg -> {});
        System.out.println("[scan] stack=" + ctx.stack().displayName()
            + " packages=" + ctx.packageSummaries().size()
            + " endpoints=" + ctx.endpoints().size()
            + " mavenProfiles=" + ctx.mavenProfiles().size()
            + " readmeIntro.length=" + ctx.readmeIntro().length()
            + " pomDescription=\"" + ctx.pomDescription() + "\"");

        // 2) Wire Ollama + ContextGeneratorService
        var aiProps = new LaunchpadAiProperties(
            Duration.ofSeconds(10), Duration.ofMinutes(2),
            new LaunchpadAiProperties.Ollama(8192),
            new LaunchpadAiProperties.Synthesis(true, 2500, 800));
        var api = OllamaApi.builder().baseUrl(OLLAMA_URL).build();
        var opts = OllamaChatOptions.builder().model(OLLAMA_MODEL).numCtx(8192).build();
        var chatModel = OllamaChatModel.builder().ollamaApi(api).defaultOptions(opts).build();
        var clientBuilder = ChatClient.builder(chatModel);
        var promptSelector = new PromptSelector(new FacetPromptComposer(),
            java.util.List.of(new com.acltabontabon.launchpad.springboot.synthesizer.SpringPromptStrategy()));
        var generator = new ContextGeneratorService(clientBuilder, promptSelector, aiProps);

        // 3) Engine + assemble. StandardsLoader is mocked - we don't need rules to verify AGENTS.md shape.
        var loader = Mockito.mock(StandardsLoader.class);
        Mockito.when(loader.loadRules(Mockito.any())).thenReturn(java.util.List.of());
        Mockito.when(loader.loadSkills(Mockito.any())).thenReturn(java.util.List.of());
        Mockito.when(loader.loadChecklists(Mockito.any())).thenReturn(java.util.List.of());
        Mockito.when(loader.loadAdapter(Mockito.any(), Mockito.any())).thenReturn(java.util.Optional.empty());
        Mockito.when(loader.loadProjectionIds(Mockito.any())).thenReturn(java.util.Set.of("claude"));
        var engine = new ContextTemplateEngine(loader, new AdapterResolver(loader), new SectionSynthesizer(generator), new com.acltabontabon.launchpad.template.companion.CompanionFileBuilder(), new AgentsPrimaryFileBuilder(), java.util.List.of(new com.acltabontabon.launchpad.template.projection.claude.ClaudeSkillsProjection()));

        var files = engine.buildFiles(ctx);
        var primary = files.stream()
            .filter(f -> f.relativePath().equals("AGENTS.md"))
            .findFirst()
            .orElseThrow();

        var outPath = Path.of("target/verification-agents.md");
        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, primary.content());

        System.out.println("================ GENERATED AGENTS.md ================");
        System.out.println(primary.content());
        System.out.println("================ END ================");
        System.out.println("[wrote] " + outPath.toAbsolutePath());
    }
}
