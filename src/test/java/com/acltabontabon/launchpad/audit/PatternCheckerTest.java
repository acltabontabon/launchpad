package com.acltabontabon.launchpad.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.scanner.PackageSummary;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Check;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatternCheckerTest {

    private final PatternChecker checker = new PatternChecker();

    @Test
    void flagsFieldInjectionInJavaSource(@TempDir Path root) throws Exception {
        var relativePath = "src/main/java/com/example/UserService.java";
        write(root, relativePath, """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            public class UserService {
                @Autowired
                private UserRepo repo;
            }
            """);

        var rule = ruleWithCheck(new Check(
            "forbid-pattern",
            "(?m)^\\s*@Autowired\\s*$\\n\\s*(?:private|protected)\\s+\\w",
            List.of("**/*.java"), null, null, null, null, null));

        var findings = checker.check(rule, contextOf(relativePath), root);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).filePath()).isEqualTo(relativePath);
        assertThat(findings.get(0).ruleId()).isEqualTo("no-field-injection");
        assertThat(findings.get(0).line()).isPositive();
    }

    @Test
    void returnsEmptyWhenPatternDoesNotMatch(@TempDir Path root) throws Exception {
        var relativePath = "src/main/java/com/example/Clean.java";
        write(root, relativePath, "public class Clean { private final Dep dep; }");

        var rule = ruleWithCheck(new Check(
            "forbid-pattern", "@Autowired", List.of("**/*.java"),
            null, null, null, null, null));

        assertThat(checker.check(rule, contextOf(relativePath), root)).isEmpty();
    }

    @Test
    void excludesFilteredFiles(@TempDir Path root) throws Exception {
        var relativePath = "src/test/java/com/example/UserServiceTest.java";
        write(root, relativePath, "@Autowired private Dep dep;");

        var rule = ruleWithCheck(new Check(
            "forbid-pattern", "@Autowired",
            List.of("**/*.java"),
            List.of("**/test/**"),
            null, null, null, null));

        assertThat(checker.check(rule, contextOf(relativePath), root)).isEmpty();
    }

    @Test
    void emitsOneFindingPerOccurrence(@TempDir Path root) throws Exception {
        var relativePath = "src/main/java/com/example/Multi.java";
        write(root, relativePath, """
            @Autowired private A a;
            @Autowired private B b;
            """);

        var rule = ruleWithCheck(new Check(
            "forbid-pattern", "@Autowired", List.of("**/*.java"),
            null, null, null, null, null));

        assertThat(checker.check(rule, contextOf(relativePath), root)).hasSize(2);
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        var path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static Rule ruleWithCheck(Check check) {
        return new Rule("no-field-injection", "Constructor Injection", "must",
            "Use constructor injection.", "Why: testability.", Scope.empty(), 10, check);
    }

    private static ProjectContext contextOf(String... files) {
        return new ProjectContext(
            "demo", "/tmp/demo",
            new StackProfile("Java", "Maven", "Spring Boot", List.of()),
            List.of(files), List.of(), Map.of(),
            List.of(new Dependency("x", "1", "runtime")),
            Map.of(), List.<PackageSummary>of(), null);
    }
}
