package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects language / build tool / framework from key project files. The
 * framework signal is what AI prompts actually need - it's the difference
 * between "give me Spring Boot advice" and "give me Maven build advice".
 */
public final class StackDetector {

    private static final ObjectMapper JSON = new ObjectMapper();

    public StackProfile detect(Path root, Map<String, String> keyFileContents) {
        var hints = new ArrayList<String>();

        if (keyFileContents.containsKey("pom.xml")) {
            var pom = keyFileContents.get("pom.xml");
            String framework = detectJavaFramework(pom);
            if (pom.contains("kotlin-maven-plugin")) {
                return new StackProfile("Kotlin", "Maven", framework, hints);
            }
            return new StackProfile("Java", "Maven", framework, hints);
        }
        if (keyFileContents.containsKey("build.gradle") || keyFileContents.containsKey("build.gradle.kts")) {
            var gradle = keyFileContents.getOrDefault("build.gradle",
                keyFileContents.getOrDefault("build.gradle.kts", ""));
            String framework = detectJavaFramework(gradle);
            boolean kotlin = gradle.contains("kotlin(") || gradle.contains("org.jetbrains.kotlin");
            return new StackProfile(kotlin ? "Kotlin" : "Java", "Gradle", framework, hints);
        }
        if (keyFileContents.containsKey("package.json")) {
            return detectNodeStack(root, keyFileContents.get("package.json"));
        }
        if (keyFileContents.containsKey("pyproject.toml") || hasFile(root, "requirements.txt")) {
            return detectPythonStack(root, keyFileContents);
        }
        if (keyFileContents.containsKey("Cargo.toml")) {
            return new StackProfile("Rust", "cargo", null, hints);
        }
        if (keyFileContents.containsKey("go.mod")) {
            String framework = detectGoFramework(keyFileContents.get("go.mod"));
            return new StackProfile("Go", "go modules", framework, hints);
        }
        if (keyFileContents.containsKey("Gemfile")) {
            String framework = keyFileContents.get("Gemfile").contains("rails") ? "Ruby on Rails" : null;
            return new StackProfile("Ruby", "bundler", framework, hints);
        }
        return StackProfile.unknown();
    }

    private static String detectJavaFramework(String buildFileContent) {
        if (buildFileContent.contains("spring-boot")) return "Spring Boot";
        if (buildFileContent.contains("org.springframework")) return "Spring";
        if (buildFileContent.contains("quarkus")) return "Quarkus";
        if (buildFileContent.contains("io.micronaut")) return "Micronaut";
        if (buildFileContent.contains("jakarta.ws.rs") || buildFileContent.contains("javax.ws.rs")) return "JAX-RS";
        if (buildFileContent.contains("io.helidon")) return "Helidon";
        return null;
    }

    private static String detectGoFramework(String goMod) {
        if (goMod.contains("github.com/gin-gonic/gin")) return "Gin";
        if (goMod.contains("github.com/labstack/echo")) return "Echo";
        if (goMod.contains("github.com/gofiber/fiber")) return "Fiber";
        return null;
    }

    private static StackProfile detectNodeStack(Path root, String packageJson) {
        try {
            JsonNode node = JSON.readTree(packageJson);
            JsonNode deps = node.path("dependencies");
            JsonNode devDeps = node.path("devDependencies");

            String framework = null;
            if (deps.has("next") || devDeps.has("next")) framework = "Next.js";
            else if (deps.has("nuxt") || devDeps.has("nuxt")) framework = "Nuxt";
            else if (deps.has("remix") || deps.has("@remix-run/node")) framework = "Remix";
            else if (deps.has("@nestjs/core")) framework = "NestJS";
            else if (deps.has("express")) framework = "Express";
            else if (deps.has("fastify")) framework = "Fastify";
            else if (deps.has("react")) framework = "React";
            else if (deps.has("vue")) framework = "Vue";
            else if (deps.has("svelte") || devDeps.has("svelte")) framework = "Svelte";

            boolean typescript = devDeps.has("typescript") || deps.has("typescript")
                || hasFile(root, "tsconfig.json");
            String language = typescript ? "TypeScript" : "JavaScript";
            String buildTool = pickNodeBuildTool(root, node);
            return new StackProfile(language, buildTool, framework, List.of());
        } catch (IOException e) {
            return new StackProfile("JavaScript", "npm", null, List.of());
        }
    }

    private static String pickNodeBuildTool(Path root, JsonNode pkg) {
        if (Files.exists(root.resolve("pnpm-lock.yaml"))) return "pnpm";
        if (Files.exists(root.resolve("yarn.lock"))) return "yarn";
        if (Files.exists(root.resolve("bun.lockb"))) return "bun";
        return "npm";
    }

    private static StackProfile detectPythonStack(Path root, Map<String, String> keyFiles) {
        var pyproject = keyFiles.getOrDefault("pyproject.toml", "");
        var reqs = readIfExists(root.resolve("requirements.txt"));
        var allDeclared = pyproject + "\n" + reqs;

        String framework = null;
        if (allDeclared.contains("django")) framework = "Django";
        else if (allDeclared.contains("fastapi")) framework = "FastAPI";
        else if (allDeclared.contains("flask")) framework = "Flask";
        else if (allDeclared.contains("pyramid")) framework = "Pyramid";
        else if (allDeclared.contains("starlette")) framework = "Starlette";

        String buildTool = "pip";
        if (pyproject.contains("[tool.poetry]")) buildTool = "Poetry";
        else if (pyproject.contains("[tool.hatch")) buildTool = "Hatch";
        else if (pyproject.contains("[tool.uv]") || Files.exists(root.resolve("uv.lock"))) buildTool = "uv";

        return new StackProfile("Python", buildTool, framework, List.of());
    }

    private static boolean hasFile(Path root, String name) {
        return Files.isRegularFile(root.resolve(name));
    }

    private static String readIfExists(Path file) {
        if (!Files.isRegularFile(file)) return "";
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }
}
