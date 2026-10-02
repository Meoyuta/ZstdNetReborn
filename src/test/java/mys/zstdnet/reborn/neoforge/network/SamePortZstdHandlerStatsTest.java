package mys.zstdnet.reborn.neoforge.network;

import io.netty.buffer.Unpooled;
import mys.zstdnet.reborn.core.benchmark.CompressionBenchmark;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import mys.zstdnet.reborn.core.stats.TrafficStats;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamePortZstdHandlerStatsTest {
    private static final ZstdNetLogger LOGGER = new ZstdNetLogger() {
        @Override public void info(String message) {}
        @Override public void warn(String message) {}
        @Override public void error(String message) {}
    };

    @TempDir Path directory;

    @Test
    void samplesByteBufsForTrainingAndBenchmarkWithoutMovingIndices() throws Exception {
        var store = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), LOGGER);
        try (var trainer = new ZstdDictionaryTrainer(store, LOGGER);
             var benchmark = new CompressionBenchmark(directory.resolve("server.properties"), LOGGER,
                 () -> 0, () -> 3, ignored -> {}, store::dictionary)) {
            assertTrue(trainer.start(Duration.ofMinutes(1), 3));
            var handler = new SamePortZstdHandler(null, new TrafficStats(), LOGGER, store, trainer, benchmark, () -> 3);
            var frameStats = handler.serverStats();
            var inbound = Unpooled.wrappedBuffer(new byte[]{9, 1, 2, 3});
            var outbound = Unpooled.wrappedBuffer(new byte[]{8, 4, 5, 6});
            try {
                inbound.skipBytes(1);
                outbound.skipBytes(1);
                frameStats.inboundSample(inbound);
                frameStats.outboundSample(outbound);

                assertEquals(1, inbound.readerIndex());
                assertEquals(1, outbound.readerIndex());
                assertEquals(2, trainer.status().sampleCount());
                assertEquals(2, benchmark.sampleCount());
            } finally {
                inbound.release();
                outbound.release();
            }
        }
    }
}
