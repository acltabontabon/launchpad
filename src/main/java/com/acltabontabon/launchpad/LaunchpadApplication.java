package com.acltabontabon.launchpad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Launchpad has two run modes:
 * <ul>
 *   <li><b>TUI</b> (default) - {@code launchpad} or {@code java -jar launchpad.jar}.
 *       Runs the interactive terminal UI.</li>
 *   <li><b>MCP server</b> - {@code launchpad mcp}. Starts a stdio MCP server that
 *       exposes Launchpad's scan, standards, and audit results to any MCP client
 *       (Claude Code, Cursor, Cline, Continue, Zed). No TUI rendering, no banner;
 *       stdout is owned by the JSON-RPC protocol and logs go to stderr.</li>
 * </ul>
 * The MCP mode activates the {@code mcp} Spring profile, which disables the TUI
 * runner (gated by {@code @Profile("!mcp")}) and triggers {@code application-mcp.properties}.
 */
@SpringBootApplication
@EnableAsync
@ImportRuntimeHints(LaunchpadRuntimeHints.class)
public class LaunchpadApplication {

    public static void main(String[] args) {
        if (args.length > 0 && "mcp".equals(args[0])) {
            // System properties activate conditions and config loading uniformly across
            // both JVM and GraalVM native modes. (@Profile is evaluated at AOT build time
            // for native images, so it can't gate beans at runtime; system properties feed
            // @ConditionalOnProperty which IS evaluated at runtime under AOT.)
            System.setProperty("launchpad.mode", "mcp");
            System.setProperty("spring.profiles.active", "mcp");
            var app = new SpringApplication(LaunchpadApplication.class);
            app.setAdditionalProfiles("mcp");
            app.run(args);
        } else {
            SpringApplication.run(LaunchpadApplication.class, args);
        }
    }
}
