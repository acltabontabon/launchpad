package com.acltabontabon.launchpad.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownAuditWriterTest {

    private final MarkdownAuditWriter writer = new MarkdownAuditWriter();

    @Test
    void groupsBySeverityInPriorityOrder() {
        var content = writer.render(List.of(
            new Finding("r1", "should", "Should-rule", "A.java", 1, "msg", "evidence"),
            new Finding("r2", "must", "Must-rule", "B.java", 2, "msg", "evidence"),
            new Finding("r3", "never", "Never-rule", "C.java", 3, "msg", "evidence")
        ));

        int neverIdx = content.indexOf("## Never");
        int mustIdx = content.indexOf("## Must");
        int shouldIdx = content.indexOf("## Should");
        assertThat(neverIdx).isPositive().isLessThan(mustIdx).isLessThan(shouldIdx);
    }

    @Test
    void rendersEmptyReportWhenNoFindings() {
        var content = writer.render(List.of());
        assertThat(content).contains("No violations found");
    }

    @Test
    void writesToDotLaunchpadAuditMd(@TempDir Path root) throws Exception {
        writer.write(root, List.of(new Finding("r1", "must", "T", "x.java", 1, "m", "e")));

        var path = root.resolve(".launchpad/audit.md");
        assertThat(path).exists();
        assertThat(Files.readString(path)).contains("r1");
    }
}
