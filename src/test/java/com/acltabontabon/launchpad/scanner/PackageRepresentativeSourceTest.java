package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageRepresentativeSourceTest {

    @Test
    void picksFileMatchingFirstSampleSymbol(@TempDir Path root) throws Exception {
        var dir = root.resolve("src/main/java/com/acme");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Other.java"), "package com.acme; class Other {}\n");
        Files.writeString(dir.resolve("UserService.java"), "package com.acme;\nclass UserService { void hello() {} }\n");

        var pkg = new PackageSummary("src/main/java/com/acme", 2, List.of("UserService", "Other"));
        var sources = List.of(
            "src/main/java/com/acme/Other.java",
            "src/main/java/com/acme/UserService.java");
        var reps = PackageRepresentativeSource.collect(root, List.of(pkg), sources);

        assertThat(reps).hasSize(1);
        assertThat(reps.get("src/main/java/com/acme")).contains("class UserService");
    }

    @Test
    void fallsBackToFirstFileWhenNoSymbolMatch(@TempDir Path root) throws Exception {
        var dir = root.resolve("src/main/java/com/acme");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AaaFirst.java"), "package com.acme; class AaaFirst {}\n");
        Files.writeString(dir.resolve("Zzz.java"), "package com.acme; class Zzz {}\n");

        var pkg = new PackageSummary("src/main/java/com/acme", 2, List.of("NoMatch"));
        var sources = List.of("src/main/java/com/acme/AaaFirst.java", "src/main/java/com/acme/Zzz.java");
        var reps = PackageRepresentativeSource.collect(root, List.of(pkg), sources);

        assertThat(reps.get("src/main/java/com/acme")).contains("AaaFirst");
    }

    @Test
    void emptyWhenNoPackages(@TempDir Path root) {
        var reps = PackageRepresentativeSource.collect(root, List.of(), List.of());
        assertThat(reps).isEmpty();
    }

    @Test
    void truncatesLongFile(@TempDir Path root) throws Exception {
        var dir = root.resolve("src");
        Files.createDirectories(dir);
        var huge = "x".repeat(300_000);
        Files.writeString(dir.resolve("Big.java"), huge);

        var pkg = new PackageSummary("src", 1, List.of("Big"));
        var reps = PackageRepresentativeSource.collect(root, List.of(pkg),
            List.of("src/Big.java"));
        // Bigger than the 24 KB per-file bytes cap -> skipped silently.
        assertThat(reps).isEmpty();
    }
}
