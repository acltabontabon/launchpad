package com.acltabontabon.launchpad.audit;

import static org.assertj.core.api.Assertions.assertThat;

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

class ImportCheckerTest {

    private final ImportChecker checker = new ImportChecker();

    @Test
    void flagsForbiddenSpringImportInDomainLayer(@TempDir Path root) throws Exception {
        var relativePath = "src/main/java/com/example/domain/User.java";
        write(root, relativePath, """
            package com.example.domain;
            import org.springframework.stereotype.Component;
            public class User {}
            """);

        var rule = ruleWithCheck(new Check(
            "forbid-import", null, null, null,
            List.of("**/domain/**"),
            List.of("org.springframework.**"),
            null, null));

        var findings = checker.check(rule, contextOf(relativePath), root);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).message()).contains("org.springframework.stereotype.Component");
    }

    @Test
    void leavesAdapterLayerUntouchedWhenInPackagesScopesOut(@TempDir Path root) throws Exception {
        var relativePath = "src/main/java/com/example/adapter/UserAdapter.java";
        write(root, relativePath, """
            package com.example.adapter;
            import org.springframework.stereotype.Component;
            public class UserAdapter {}
            """);

        var rule = ruleWithCheck(new Check(
            "forbid-import", null, null, null,
            List.of("**/domain/**"),
            List.of("org.springframework.**"),
            null, null));

        assertThat(checker.check(rule, contextOf(relativePath), root)).isEmpty();
    }

    @Test
    void wildcardFqnMatchesExactPrefix() {
        assertThat(ImportChecker.matchesFqn("org.springframework.stereotype.Service", "org.springframework.**")).isTrue();
        assertThat(ImportChecker.matchesFqn("org.spring.other", "org.springframework.**")).isFalse();
        assertThat(ImportChecker.matchesFqn("java.util.List", "java.util.List")).isTrue();
        assertThat(ImportChecker.matchesFqn("java.util.List", "java.util.*")).isTrue();
        assertThat(ImportChecker.matchesFqn("java.util.concurrent.atomic.AtomicInteger", "java.util.*")).isFalse();
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        var path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static Rule ruleWithCheck(Check check) {
        return new Rule("domain-no-spring", "Domain Does Not Depend on Infrastructure", "never",
            "Domain doesn't import Spring.", "Why: portability.", Scope.empty(), 1, check);
    }

    private static ProjectContext contextOf(String... files) {
        return new ProjectContext(
            "demo", "/tmp/demo",
            new StackProfile("Java", "Maven", "Spring Boot", List.of()),
            List.of(files), List.of(), Map.of(),
            List.of(), Map.of(), List.<PackageSummary>of(), null);
    }
}
