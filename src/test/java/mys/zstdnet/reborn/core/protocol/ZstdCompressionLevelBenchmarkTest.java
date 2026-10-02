package mys.zstdnet.reborn.core.protocol;

import mys.zstdnet.reborn.core.dictionary.DictionaryFixtures;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("benchmark")
class ZstdCompressionLevelBenchmarkTest {
    // Minecraft's VarInt21 packet framing rejects payloads larger than 2 MiB.
    private static final int VANILLA_MAX_PACKET_BYTES = 2 * 1024 * 1024;
    private static final int[] LEVELS = selectedLevels();
    private static final int WARMUP_ROUNDS = Integer.getInteger("zstdnet.benchmark.warmups", 0);
    private static final int MEASURE_ROUNDS = Integer.getInteger("zstdnet.benchmark.rounds", 1);
    private static final String CORPUS_SELECTION = System.getProperty("zstdnet.benchmark.corpus", "mixed");
    private static final double BANDWIDTH_MBPS = Double.parseDouble(
            System.getProperty("zstdnet.benchmark.bandwidthMbps", "100"));
    private static final Corpus[] CORPORA = createCorpora();

    private static Result[] measure(Corpus corpus, ZstdDictionary dictionary) throws IOException {
        var results = new Result[LEVELS.length];
        for (var levelIndex = 0; levelIndex < LEVELS.length; levelIndex++) {
            var level = LEVELS[levelIndex];
            for (var round = 0; round < WARMUP_ROUNDS; round++) {
                run(corpus, level, dictionary, false);
            }
            var encodedBytes = 0L;
            var encodeNanos = 0L;
            var decodeNanos = 0L;
            for (var round = 0; round < MEASURE_ROUNDS; round++) {
                var measured = run(corpus, level, dictionary, false);
                encodedBytes += measured.encodedBytes();
                encodeNanos += measured.encodeNanos();
                decodeNanos += measured.decodeNanos();
            }

            var frameCount = (long) corpus.frames().length * MEASURE_ROUNDS;
            var averageFrameBytes = (double) encodedBytes / frameCount;
            var averageEncodeMicros = encodeNanos / 1_000.0 / frameCount;
            var averageDecodeMicros = decodeNanos / 1_000.0 / frameCount;
            var wireMillis = averageFrameBytes * 8.0 / (BANDWIDTH_MBPS * 1_000.0);
            results[levelIndex] = new Result(
                    level,
                    averageFrameBytes,
                    averageEncodeMicros,
                    averageDecodeMicros,
                    averageEncodeMicros + averageDecodeMicros,
                    wireMillis,
                    (averageEncodeMicros + averageDecodeMicros) / 1_000.0 + wireMillis
            );
        }
        return results;
    }

    private static Measurement run(
            Corpus corpus, int level, ZstdDictionary dictionary,
            boolean assertRoundTrip
    ) throws IOException {
        long encodedBytes = 0;
        long encodeNanos = 0;
        long decodeNanos = 0;
        try (
                var encoder = new ZstdPersistentStreamCodec(level, dictionary);
                var decoder = new ZstdPersistentStreamCodec(level, dictionary)
        ) {
            for (var raw : corpus.frames()) {
                ByteBuf input = Unpooled.wrappedBuffer(raw);
                ByteBuf frame = Unpooled.buffer(raw.length + 64);
                ByteBuf decoded = Unpooled.buffer(raw.length);
                try {
                    var encodeStart = System.nanoTime();
                    ZstdFrameCodec.writeFrame(input, encoder, dictionary != null, frame);
                    encodeNanos += System.nanoTime() - encodeStart;
                    encodedBytes += frame.readableBytes();
                    var decodeStart = System.nanoTime();
                    ZstdFrameCodec.readFrame(frame, decoder, decoded);
                    decodeNanos += System.nanoTime() - decodeStart;
                    if (decoded.readableBytes() != raw.length) {
                        throw new IOException("stream benchmark round-trip length mismatch");
                    }
                    if (assertRoundTrip) {
                        for (var i = 0; i < raw.length; i++) {
                            assertEquals(raw[i], decoded.getByte(decoded.readerIndex() + i),
                                    "round-trip failed at level " + level);
                        }
                    }
                } finally {
                    input.release();
                    frame.release();
                    decoded.release();
                }
            }
        }
        return new Measurement(encodedBytes, encodeNanos, decodeNanos);
    }

    private static void assertRoundTrips(Corpus corpus, Result[] results, ZstdDictionary dictionary) throws IOException {
        for (var result : results) {
            run(corpus, result.level(), dictionary, true);
        }
    }

    private static void printReport(String corpusName, String mode, Result[] results) {
        var fastest = Arrays.stream(results)
                .min((left, right) -> Double.compare(left.codecMicros(), right.codecMicros()))
                .orElseThrow();
        var smallest = Arrays.stream(results)
                .min((left, right) -> Double.compare(left.averageFrameBytes(), right.averageFrameBytes()))
                .orElseThrow();
        var recommended = Arrays.stream(results)
                .min((left, right) -> Double.compare(left.estimatedLatencyMillis(), right.estimatedLatencyMillis()))
                .orElseThrow();

        System.out.println();
        System.out.printf(Locale.ROOT,
            "Zstd max-frame benchmark: %s, %s, raw frame size=%d bytes, bandwidth=%.1f Mbps%n",
            corpusName, mode, VANILLA_MAX_PACKET_BYTES, BANDWIDTH_MBPS);
        System.out.println("level | frame bytes | encode us | decode us | codec us | wire ms | estimated ms | Pareto");
        for (var result : results) {
            System.out.printf(Locale.ROOT, "%5d | %11.2f | %10.2f | %10.2f | %8.2f | %7.2f | %12.2f | %s%n",
                    result.level(), result.averageFrameBytes(), result.encodeMicros(), result.decodeMicros(),
                    result.codecMicros(), result.wireMillis(), result.estimatedLatencyMillis(),
                    isParetoOptimal(result, results) ? "*" : "");
        }
        System.out.printf(Locale.ROOT,
                "Fastest codec: level %d; smallest frame: level %d; recommended for estimated latency: level %d.%n",
                fastest.level(), smallest.level(), recommended.level());
    }

    private static boolean isParetoOptimal(Result candidate, Result[] results) {
        return Arrays.stream(results).noneMatch(other -> other != candidate
                && other.codecMicros() <= candidate.codecMicros()
                && other.averageFrameBytes() <= candidate.averageFrameBytes()
                && (other.codecMicros() < candidate.codecMicros()
                || other.averageFrameBytes() < candidate.averageFrameBytes()));
    }

    private static long totalBytes(byte[][] values) {
        var total = 0L;
        for (var value : values) {
            total += value.length;
        }
        return total;
    }

    private static Corpus[] createCorpora() {
        var samples = DictionaryFixtures.samples();
        var all = new Corpus[]{
            new Corpus("structured", structuredFrame(samples, new SplittableRandom(0x5EEDL), false)),
            new Corpus("mixed", structuredFrame(samples, new SplittableRandom(0xBEEFL), true)),
            new Corpus("random", randomFrame(new SplittableRandom(0xC0FFEE))),
        };
        if ("all".equalsIgnoreCase(CORPUS_SELECTION)) return all;
        var selected = Arrays.stream(all)
            .filter(corpus -> corpus.name().equalsIgnoreCase(CORPUS_SELECTION))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "zstdnet.benchmark.corpus must be structured, mixed, random or all"));
        return new Corpus[]{selected};
    }

    private static int[] selectedLevels() {
        var configured = System.getProperty("zstdnet.benchmark.levels",
            "1,3,5,7,9,11,13,15,17,19,21,22");
        if ("all".equalsIgnoreCase(configured)) {
            var levels = new int[22];
            for (var i = 0; i < levels.length; i++) levels[i] = i + 1;
            return levels;
        }
        var values = configured.split(",");
        var levels = new int[values.length];
        for (var i = 0; i < values.length; i++) {
            levels[i] = Integer.parseInt(values[i].trim());
            if (levels[i] < 1 || levels[i] > 22) {
                throw new IllegalArgumentException("benchmark levels must be between 1 and 22");
            }
        }
        return levels;
    }

    private static byte[][] structuredFrame(byte[][] samples, SplittableRandom random, boolean mixed) {
        var frame = new byte[VANILLA_MAX_PACKET_BYTES];
        var position = 0;
        var block = 0;
        while (position < frame.length) {
            var length = Math.min(4096, frame.length - position);
            if (mixed && block % 4 == 0) {
                var randomBlock = new byte[length];
                random.nextBytes(randomBlock);
                System.arraycopy(randomBlock, 0, frame, position, length);
            } else {
                var sample = samples[random.nextInt(samples.length)];
                for (var i = 0; i < length; i++) {
                    frame[position + i] = sample[i % sample.length];
                }
                for (var i = 0; i < length; i += 257) {
                    frame[position + i] ^= (byte) random.nextInt(256);
                }
            }
            position += length;
            block++;
        }
        return new byte[][]{frame};
    }

    private static byte[][] randomFrame(SplittableRandom random) {
        var frame = new byte[VANILLA_MAX_PACKET_BYTES];
        random.nextBytes(frame);
        return new byte[][]{frame};
    }

    @Test
    @EnabledIfSystemProperty(named = "zstdnet.benchmark.enabled", matches = "true")
    void maxFrameBenchmarkReportsLatencyTradeoffWithAndWithoutDictionary() throws Exception {
        var dictionary = DictionaryFixtures.dictionary();
        for (var corpus : CORPORA) {
            var withoutDictionary = measure(corpus, null);
            var withDictionary = measure(corpus, dictionary);

            printReport(corpus.name(), "without dictionary", withoutDictionary);
            printReport(corpus.name(), "with dictionary", withDictionary);
            assertRoundTrips(corpus, withoutDictionary, null);
            assertRoundTrips(corpus, withDictionary, dictionary);
        }
    }

    private record Corpus(String name, byte[][] frames) {
    }

    private record Result(
            int level,
            double averageFrameBytes,
            double encodeMicros,
            double decodeMicros,
            double codecMicros,
            double wireMillis,
            double estimatedLatencyMillis
    ) {
    }

    private record Measurement(long encodedBytes, long encodeNanos, long decodeNanos) {
    }
}
