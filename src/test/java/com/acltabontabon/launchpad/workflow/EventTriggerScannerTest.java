package com.acltabontabon.launchpad.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.model.WorkflowType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventTriggerScannerTest {

    @Test
    void detectsScheduledAndListenerTriggers(@TempDir Path root) throws Exception {
        Path src = root.resolve("com/acme/Jobs.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package com.acme;

            class Jobs {
                @Scheduled(cron = "0 0 * * * *")
                public void processDailyReport() {}

                @KafkaListener(topics = "orders")
                void onOrderPlaced(String payload) {}
            }
            """);

        var triggers = EventTriggerScanner.scan(root, List.of("com/acme/Jobs.java"));

        assertThat(triggers).hasSize(2);

        var scheduled = triggers.stream()
            .filter(t -> t.annotation().equals("Scheduled")).findFirst().orElseThrow();
        assertThat(scheduled.type()).isEqualTo(WorkflowType.SCHEDULED);
        assertThat(scheduled.method()).isEqualTo("processDailyReport");
        assertThat(scheduled.file()).isEqualTo("com/acme/Jobs.java");

        var listener = triggers.stream()
            .filter(t -> t.annotation().equals("KafkaListener")).findFirst().orElseThrow();
        assertThat(listener.type()).isEqualTo(WorkflowType.EVENT_DRIVEN);
        assertThat(listener.method()).isEqualTo("onOrderPlaced");
    }

    @Test
    void recordsTriggerEvenWhenMethodNotFound(@TempDir Path root) throws Exception {
        Path src = root.resolve("Dangling.java");
        Files.writeString(src, "class Dangling {\n    @EventListener\n}\n");

        var triggers = EventTriggerScanner.scan(root, List.of("Dangling.java"));

        assertThat(triggers).hasSize(1);
        assertThat(triggers.get(0).method()).isEmpty();
        assertThat(triggers.get(0).type()).isEqualTo(WorkflowType.EVENT_DRIVEN);
    }

    @Test
    void ignoresNonJvmAndMissingFiles(@TempDir Path root) {
        assertThat(EventTriggerScanner.scan(root, List.of("readme.md", "missing.java"))).isEmpty();
        assertThat(EventTriggerScanner.scan(null, List.of("x.java"))).isEmpty();
        assertThat(EventTriggerScanner.scan(root, List.of())).isEmpty();
    }
}
