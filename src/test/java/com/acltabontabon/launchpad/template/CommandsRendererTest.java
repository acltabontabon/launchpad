package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.SpringProfile;
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
    void mavenNonSpringFallsBackToExecJava() {
        var stack = new StackProfile("Java", "Maven", null, List.of());
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("./mvnw exec:java");
        assertThat(out).doesNotContain("spring-boot:run");
    }

    @Test
    void gradleSpringEmitsBootRun() {
        var stack = new StackProfile("Java", "Gradle", "Spring Boot", List.of());
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("./gradlew bootRun");
        assertThat(out).contains("./gradlew test");
    }

    @Test
    void npmEmitsInstallDevTestBuild() {
        var stack = new StackProfile("TypeScript", "npm", "Next.js", List.of());
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("npm install");
        assertThat(out).contains("npm run dev");
        assertThat(out).contains("npm test");
        assertThat(out).contains("npm run build");
    }

    @Test
    void goEmitsRunTestBuild() {
        var stack = new StackProfile("Go", "go", null, List.of());
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("go run .");
        assertThat(out).contains("go test ./...");
        assertThat(out).contains("go build ./...");
    }

    @Test
    void terraformEmitsInitPlanApply() {
        var stack = new StackProfile("HCL", "Terraform", null, List.of());
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("terraform init");
        assertThat(out).contains("terraform plan");
        assertThat(out).contains("terraform apply");
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
    void buildToolMatchingIsCaseInsensitive() {
        var stack = new StackProfile("Python", "PIP", null, List.of());
        var out = CommandsRenderer.render(stack);

        assertThat(out).contains("pip install -r requirements.txt");
    }
}
