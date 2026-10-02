package mys.zstdnet.reborn.core.protocol;

import mys.zstdnet.reborn.core.dictionary.DictionaryFixtures;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;

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
                var encoded = encodeAll(corpus.frames(), level, dictionary);
                decodeAll(encoded, dictionary);
            }

            var encoded = new byte[corpus.frames().length][];
            var encodedBytes = 0L;
            var encodeStart = System.nanoTime();
            for (var round = 0; round < MEASURE_ROUNDS; round++) {
                var measured = encodeAll(corpus.frames(), level, dictionary);
                encodedBytes += totalBytes(measured);
                encoded = measured;
            }
            var encodeNanos = System.nanoTime() - encodeStart;

            var decodeStart = System.nanoTime();
            for (var round = 0; round < MEASURE_ROUNDS; round++) {
                decodeAll(encoded, dictionary);
            }
            var decodeNanos = System.nanoTime() - decodeStart;

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
                    (averageEncodeMicros + averageDecodeMicros) / 1_000.0 + wireMillis,
                    encoded
            );
        }
        return results;
    }

    private static byte[][] encodeAll(byte[][] frames, int level, ZstdDictionary dictionary) throws IOException {
        var encoded = new byte[frames.length][];
        for (var i = 0; i < frames.length; i++) {
            encoded[i] = ZstdFrameCodec.compressFrame(frames[i], level, dictionary);
        }
        return encoded;
    }

    private static void decodeAll(byte[][] encoded, ZstdDictionary dictionary) throws IOException {
        for (var frame : encoded) {
            ZstdFrameCodec.readFrame(new ByteArrayInputStream(frame), dictionary);
        }
    }

    private static void assertRoundTrips(Corpus corpus, Result[] results, ZstdDictionary dictionary) throws IOException {
        for (var result : results) {
            for (var i = 0; i < corpus.frames().length; i++) {
                var restored = ZstdFrameCodec.readFrame(
                        new ByteArrayInputStream(result.encoded()[i]), dictionary);
                assertEquals(corpus.frames()[i].length, restored.length,
                        "decoded size differs at level " + result.level());
                assertArrayEquals(corpus.frames()[i], restored,
                        "round-trip failed at level " + result.level());
            }
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
            double estimatedLatencyMillis,
            byte[][] encoded
    ) {
    }
}
