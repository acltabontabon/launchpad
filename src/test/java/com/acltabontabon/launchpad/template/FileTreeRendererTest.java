package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.PackageSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileTreeRendererTest {

    @Test
    void rendersBoundedTreeWithSampleSymbols() {
        var packages = List.of(
            new PackageSummary("com/acme/web", 3, List.of("UserController", "OrderController")),
            new PackageSummary("com/acme/service", 5, List.of("UserService", "OrderService", "BillingService"))
        );
        var out = FileTreeRenderer.render(packages);

        assertThat(out).startsWith("```\n").endsWith("```\n");
        assertThat(out).contains("com/acme/web/");
        assertThat(out).contains("UserController");
        assertThat(out).contains("OrderService");
    }

    @Test
    void truncatesAfterTwentyPackages() {
        var many = new java.util.ArrayList<PackageSummary>();
        for (int i = 0; i < 25; i++) {
            many.add(new PackageSummary("com/acme/pkg" + i, 1, List.of("Class" + i)));
        }
        var out = FileTreeRenderer.render(many);
        assertThat(out).contains("... and 5 more");
        // Only the first 20 should appear; pkg24 should not.
        assertThat(out).doesNotContain("pkg24");
    }

    @Test
    void emptyPackagesProducesPlaceholder() {
        var out = FileTreeRenderer.render(List.of());
        assertThat(out).contains("no source packages detected");
        assertThat(out).doesNotContain("```");
    }

    @Test
    void usesFileCountWhenNoSymbols() {
        var packages = List.of(new PackageSummary("scripts", 7, List.of()));
        var out = FileTreeRenderer.render(packages);
        assertThat(out).contains("scripts/");
        assertThat(out).contains("7 files");
    }
}
