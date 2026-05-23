package com.acltabontabon.launchpad.eval;

import java.util.List;

/**
 * Expected facts about a small project under src/test/resources/fixtures/.
 * The scanner eval asserts these directly; the generation eval uses them
 * as substring expectations for the LLM output.
 */
public record ProjectFixture(
    String name,
    String classpathDir,            // resolved relative to src/test/resources/fixtures/
    String expectedLanguage,
    String expectedBuildTool,
    String expectedFramework,       // null = no specific framework expected
    String expectedEntryPointBasename,  // e.g. "UsersApplication.java"
    List<String> expectedDepNames,  // at least these must appear in dependency list
    List<String> expectedSymbolHints,   // package summary should mention these
    List<String> expectedSummaryKeywords   // used by generation eval as substring checks
) {

    public static List<ProjectFixture> all() {
        return List.of(
            new ProjectFixture(
                "spring-boot",
                "spring-boot",
                "Java", "Maven", "Spring Boot",
                "UsersApplication.java",
                List.of("spring-boot-starter-web", "spring-boot-starter-data-jpa", "postgresql"),
                List.of("UserController", "UserService", "UserRepository", "OrderController"),
                List.of("Spring", "JPA", "REST")
            ),
            new ProjectFixture(
                "spring-boot-starter",
                "spring-boot-starter",
                "Java", "Maven", "Spring Boot",
                null,   // a starter library has no application entry point
                List.of("spring-boot-autoconfigure", "spring-boot-configuration-processor"),
                List.of("WidgetAutoConfiguration", "WidgetProperties", "WidgetService"),
                List.of("autoconfiguration", "starter", "ConditionalOnClass")
            ),
            new ProjectFixture(
                "nextjs",
                "nextjs",
                "TypeScript", "npm", "Next.js",
                null,   // Next app-router has no single file entry point in the current heuristic
                List.of("next", "react", "@prisma/client", "tailwindcss"),
                List.of("HomePage", "ProductsPage", "ProductList"),
                List.of("Next", "React", "TypeScript")
            ),
            new ProjectFixture(
                "fastapi",
                "fastapi",
                "Python", "pip", "FastAPI",
                "main.py",
                List.of("fastapi", "uvicorn", "sqlalchemy", "pydantic"),
                List.of("Item", "list_items"),
                List.of("FastAPI", "router")
            ),
            new ProjectFixture(
                "rust-cli",
                "rust-cli",
                "Rust", "cargo", null,
                "main.rs",
                List.of("clap", "regex", "serde_json"),
                List.of("Matcher", "compile", "run"),
                List.of("CLI", "Rust")
            ),
            new ProjectFixture(
                "databricks-recon",
                "databricks-recon",
                "Python", "Poetry", "Databricks",
                null,   // pipeline has no app-shaped entry point
                List.of("databricks-sdk", "pyspark", "databricks/databricks"),
                List.of(),  // no Java symbols to summarise from
                List.of("Databricks", "DLT", "Terraform")
            )
        );
    }
}
