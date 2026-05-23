package com.acltabontabon.launchpad;

import com.acltabontabon.launchpad.config.LaunchpadAiProperties;
import com.acltabontabon.launchpad.config.LaunchpadTaskProperties;
import com.acltabontabon.launchpad.config.ProjectRegistry;
import com.acltabontabon.launchpad.config.RegisteredProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Launchpad has four run modes, selected by the first CLI argument:
 * <ul>
 *   <li><b>TUI</b> (default) - {@code launchpad} or {@code java -jar launchpad.jar}.
 *       Runs the interactive terminal UI.</li>
 *   <li><b>MCP server</b> - {@code launchpad mcp}. Starts a stdio MCP server that
 *       exposes Launchpad's scan, standards, and audit results to any MCP client
 *       (Claude Code, Cursor, Cline, Continue, Zed). No TUI rendering, no banner;
 *       stdout is owned by the JSON-RPC protocol and logs go to stderr. The
 *       {@code LAUNCHPAD_DEFAULT_PROJECT} env var sets the default project name
 *       used when MCP tools are called without a {@code project} argument.</li>
 *   <li><b>register</b> - {@code launchpad register <path>} adds one project,
 *       {@code launchpad register --scan <dir>} enrolls every direct subdirectory
 *       under {@code <dir>} that already has a {@code .launchpad/scan.json}.
 *       Idempotent.</li>
 *   <li><b>unregister</b> - {@code launchpad unregister <name>} removes one
 *       registry entry. Does not touch project files.</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties({LaunchpadAiProperties.class, LaunchpadTaskProperties.class})
@ImportRuntimeHints(LaunchpadRuntimeHints.class)
public class LaunchpadApplication {

    public static void main(String[] args) {
        if (args.length > 0) {
            switch (args[0]) {
                case "mcp" -> runMcp(args);
                case "register" -> { handleRegister(args); return; }
                case "unregister" -> { handleUnregister(args); return; }
                default -> SpringApplication.run(LaunchpadApplication.class, args);
            }
        } else {
            SpringApplication.run(LaunchpadApplication.class, args);
        }
    }

    private static void runMcp(String[] args) {
        // System properties activate conditions and config loading uniformly across
        // both JVM and GraalVM native modes. (@Profile is evaluated at AOT build time
        // for native images, so it can't gate beans at runtime; system properties feed
        // @ConditionalOnProperty which IS evaluated at runtime under AOT.)
        System.setProperty("launchpad.mode", "mcp");
        System.setProperty("spring.profiles.active", "mcp");
        var app = new SpringApplication(LaunchpadApplication.class);
        app.setAdditionalProfiles("mcp");
        app.run(args);
    }

    private static void handleRegister(String[] args) {
        var rest = Arrays.asList(args).subList(1, args.length);
        if (rest.isEmpty()) {
            System.err.println("usage: launchpad register <absolute-path>");
            System.err.println("       launchpad register --scan <dir>");
            System.exit(2);
            return;
        }
        var registry = new ProjectRegistry();
        if ("--scan".equals(rest.get(0))) {
            if (rest.size() < 2) {
                System.err.println("usage: launchpad register --scan <dir>");
                System.exit(2);
                return;
            }
            var dir = Path.of(rest.get(1)).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                System.err.println("not a directory: " + dir);
                System.exit(1);
                return;
            }
            var added = scanAndEnroll(registry, dir);
            System.out.println("Registered " + added.size() + " project(s) under " + dir);
            for (var name : added) System.out.println("  - " + name);
            return;
        }
        var path = Path.of(rest.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            System.err.println("not a directory: " + path);
            System.exit(1);
            return;
        }
        var entry = registry.register(path, null);
        System.out.println("registered " + entry.name() + " -> " + entry.path());
    }

    private static List<String> scanAndEnroll(ProjectRegistry registry, Path dir) {
        var added = new ArrayList<String>();
        try (var stream = Files.list(dir)) {
            stream
                .filter(Files::isDirectory)
                .filter(p -> Files.isRegularFile(p.resolve(".launchpad").resolve("scan.json")))
                .forEach(p -> {
                    var before = registry.findByPath(p);
                    var entry = registry.register(p, null);
                    if (before.isEmpty()) added.add(entry.name());
                });
        } catch (java.io.IOException e) {
            System.err.println("scan failed: " + e.getMessage());
            System.exit(1);
        }
        return added;
    }

    private static void handleUnregister(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: launchpad unregister <name>");
            System.exit(2);
            return;
        }
        var registry = new ProjectRegistry();
        var name = args[1];
        if (registry.remove(name)) {
            System.out.println("removed " + name);
        } else {
            System.err.println("no project named '" + name + "' in the registry");
            System.exit(1);
        }
    }
}
