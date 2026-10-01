package mys.zstdnet.reborn.core.benchmark;

import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressionBenchmarkSchedulingTest {
    @TempDir
    Path directory;

    @Test
    void benchmarkUsesBoundedRepresentativeWorkingSet() {
        var samples = java.util.stream.IntStream.range(0, 4096)
                .mapToObj(ignored -> new byte[4096])
                .toList();
        var bounded = CompressionBenchmark.boundedCorpus(samples);
        assertTrue(bounded.size() < samples.size());
        assertTrue(bounded.stream().mapToLong(sample -> sample.length).sum() <= 1024 * 1024);
    }

    @Test
    void automaticSelectionRejectsCompressionWinsOutsideRelativeLatencyBudget() {
        var fastest = new CompressionBenchmark.Result("complete", 3, true, 40.0,
                0.0040, 0.0040, 100, 1, 30);
        var acceptable = new CompressionBenchmark.Result("complete", 5, true, 30.0,
                0.0048, 0.0048, 100, 1, 30);
        var tooSlow = new CompressionBenchmark.Result("complete", 9, true, 10.0,
                0.0060, 0.0060, 100, 1, 30);

        assertEquals(5, CompressionBenchmark.selectBestCandidate(List.of(fastest, acceptable, tooSlow)).level());
    }

    @Test
    void automaticBenchmarkCandidatesAreLimitedToLevelsFiveThroughThirteen() {
        assertEquals(List.of(5, 6, 7, 8, 9, 10, 11, 12, 13), CompressionBenchmark.candidateLevels());
    }

    @Test
    void schedulerChecksAutomaticallyWithoutServerTick() throws Exception {
        var logger = new ZstdNetLogger() {
            public void info(String message) {}
            public void warn(String message) {}
            public void error(String message) {}
        };
        try (var benchmark = new CompressionBenchmark(
                directory.resolve("server.properties"), logger,
                () -> 1, () -> 3, ignored -> {}, () -> null)) {
            var deadline = System.nanoTime() + 5_000_000_000L;
            while (benchmark.result().samples() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertEquals("waiting", benchmark.result().state());
            assertTrue(benchmark.result().samples() == 0);
        }
    }

    @Test
    void fiveHundredTwelveSamplesAreEnoughToStartBenchmark() throws Exception {
        var logger = new ZstdNetLogger() {
            public void info(String message) {}
            public void warn(String message) {}
            public void error(String message) {}
        };
        try (var benchmark = new CompressionBenchmark(
                directory.resolve("server.properties"), logger,
                () -> 1, () -> 3, ignored -> {}, () -> null)) {
            for (var i = 0; i < 512; i++) {
                benchmark.capture(new byte[]{(byte) i, 1, 2, 3});
            }
            assertTrue(benchmark.start());
            var deadline = System.nanoTime() + 10_000_000_000L;
            while (!"complete".equals(benchmark.result().state()) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertEquals("complete", benchmark.result().state());
        }
    }

    @Test
    void intervalCanBeChangedAndIsPersisted() throws Exception {
        var logger = new ZstdNetLogger() {
            public void info(String message) {}
            public void warn(String message) {}
            public void error(String message) {}
        };
        try (var benchmark = new CompressionBenchmark(
                directory.resolve("server.properties"), logger,
                () -> 0, () -> 3, ignored -> {}, () -> null)) {
            benchmark.setInterval(7);
            assertEquals(7, benchmark.intervalMinutes());
            assertEquals(7, benchmark.result().intervalMinutes());
            assertTrue(Files.readString(directory.resolve("server.properties"))
                    .contains("benchmark-interval-minutes=7"));
        }
        try (var reloaded = new CompressionBenchmark(
                directory.resolve("server.properties"), logger,
                () -> 0, () -> 3, ignored -> {}, () -> null)) {
            assertEquals(7, reloaded.intervalMinutes());
            assertEquals(7, reloaded.result().intervalMinutes());
        }
    }
}
