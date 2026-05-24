package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.springboot.runtime.SpringProfile;
import com.acltabontabon.launchpad.scanner.StackProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandsRendererTest {

    @Test
    void mavenSpringEmitsBootRun() {
        var stack = new StackProfile("Java", "Maven", "Spring Boot", List.of());
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("## Commands");
        assertThat(out).contains("```bash");
        assertThat(out).contains("./mvnw spring-boot:run");
        assertThat(out).contains("./mvnw test");
        assertThat(out).contains("./mvnw package");
        assertThat(out).doesNotContain("./mvnw -Pnative");
    }

    @Test
    void mavenSpringWithNativeImageProfileAddsNativeCompile() {
        var spring = new SpringProfile(true, false, false, false, false, false, false, false, false, false, true, false);
        var stack = new StackProfile("Java", "Maven", "Spring Boot", List.of()).withSpringProfile(spring);
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("./mvnw -Pnative native:compile");
    }

    @Test
    void buildToolMatchingIsCaseInsensitive() {
        var stack = new StackProfile("Java", "MAVEN", "Spring Boot", List.of());
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("./mvnw spring-boot:run");
    }

    @Test
    void unknownBuildToolEmitsPlaceholder() {
        var stack = new StackProfile("Unknown", null, null, List.of());
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("## Commands");
        assertThat(out).contains("Build tool not detected");
        assertThat(out).doesNotContain("```bash");
    }

    @Test
    void nonMavenBuildToolEmitsPlaceholder() {
        var stack = new StackProfile("Java", "Gradle", "Spring Boot", List.of());
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("Build tool not detected");
        assertThat(out).doesNotContain("```bash");
    }
}
