package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassClassifierTest {

    @Test
    void classifiesRecord(@TempDir Path root) throws Exception {
        var rel = "src/main/java/foo/LoanApplication.java";
        write(root, rel, """
            package foo;
            public record LoanApplication(String id, int amount) {}
            """);
        var fact = ClassClassifier.classify(root, rel);
        assertThat(fact.kind()).isEqualTo(ClassFact.Kind.RECORD);
        assertThat(fact.name()).isEqualTo("LoanApplication");
        assertThat(fact.leafPackage()).isEqualTo("foo");
    }

    @Test
    void classifiesEnum(@TempDir Path root) throws Exception {
        var rel = "src/main/java/foo/CreditTier.java";
        write(root, rel, """
            package foo;
            public enum CreditTier { PRIME, NEAR_PRIME, SUBPRIME }
            """);
        assertThat(ClassClassifier.classify(root, rel).kind()).isEqualTo(ClassFact.Kind.ENUM);
    }

    @Test
    void classifiesInterface(@TempDir Path root) throws Exception {
        var rel = "src/main/java/foo/RiskModel.java";
        write(root, rel, """
            package foo;
            public interface RiskModel { double score(int x); }
            """);
        assertThat(ClassClassifier.classify(root, rel).kind()).isEqualTo(ClassFact.Kind.INTERFACE);
    }

    @Test
    void classifiesRestController(@TempDir Path root) throws Exception {
        var rel = "src/main/java/foo/LoanController.java";
        write(root, rel, """
            package foo;
            @RestController
            public class LoanController {
                @GetMapping("/hello") public String hi() { return "hi"; }
            }
            """);
        var fact = ClassClassifier.classify(root, rel);
        assertThat(fact.kind()).isEqualTo(ClassFact.Kind.REST_CONTROLLER);
        assertThat(fact.name()).isEqualTo("LoanController");
    }

    @Test
    void classifiesPlainClass(@TempDir Path root) throws Exception {
        var rel = "src/main/java/foo/Helper.java";
        write(root, rel, """
            package foo;
            public final class Helper { void run() {} }
            """);
        assertThat(ClassClassifier.classify(root, rel).kind()).isEqualTo(ClassFact.Kind.CLASS);
    }

    @Test
    void returnsNullForFileWithoutTopLevelDeclaration(@TempDir Path root) throws Exception {
        var rel = "src/main/java/foo/Nothing.java";
        write(root, rel, "// just a comment\npackage foo;\n");
        assertThat(ClassClassifier.classify(root, rel)).isNull();
    }

    @Test
    void leafPackageStrippingWorksForDeepPaths() {
        assertThat(ClassClassifier.leafPackage("src/main/java/com/acme/risk/RiskModel.java")).isEqualTo("risk");
        assertThat(ClassClassifier.leafPackage("Foo.java")).isEqualTo("");
    }

    private static void write(Path root, String rel, String content) throws Exception {
        var p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
