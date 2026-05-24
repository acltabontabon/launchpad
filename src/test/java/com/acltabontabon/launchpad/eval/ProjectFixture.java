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
            )
        );
    }
}
