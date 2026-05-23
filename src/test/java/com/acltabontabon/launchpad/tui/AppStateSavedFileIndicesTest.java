package com.acltabontabon.launchpad.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class AppStateSavedFileIndicesTest {

    @Test
    void concurrentAddAndContainsDoesNotThrowAndLosesNoUpdates() throws Exception {
        AppState state = new AppState();
        int writerCount = 4;
        int adds = 2_500;
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean readerSawTrouble = new AtomicBoolean(false);

        Runnable reader = () -> {
            try {
                start.await();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                while (System.nanoTime() < deadline) {
                    for (int i = 0; i < writerCount * adds; i++) {
                        // The TUI render loop's only read pattern.
                        state.savedFileIndices.contains(i);
                    }
                }
            } catch (Throwable t) {
                readerSawTrouble.set(true);
            }
        };
        pool.submit(reader);

        for (int w = 0; w < writerCount; w++) {
            int base = w * adds;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < adds; i++) {
                        state.savedFileIndices.add(base + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(readerSawTrouble).isFalse();
        assertThat(state.savedFileIndices).hasSize(writerCount * adds);
    }

    @Test
    void resetScanLatchClearsSavedIndicesWithoutReplacingTheReference() {
        AppState state = new AppState();
        state.savedFileIndices.add(0);
        state.savedFileIndices.add(7);
        var ref = state.savedFileIndices;

        state.resetScanLatch();

        assertThat(state.savedFileIndices).isSameAs(ref);
        assertThat(state.savedFileIndices).isEmpty();
    }
}
