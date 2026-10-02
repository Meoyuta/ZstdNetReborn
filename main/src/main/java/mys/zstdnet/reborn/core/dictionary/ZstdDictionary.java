package mys.zstdnet.reborn.core.dictionary;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.LinkedHashMap;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public final class ZstdDictionary {
    public static final int MIN_BYTES = 256;
    /** Maximum dictionary size for server-to-client (downlink) dictionaries. */
    public static final int MAX_DOWNLINK_BYTES = 128 * 1024;
    /** Maximum dictionary size for client-to-server (uplink) dictionaries. */
    public static final int MAX_UPLINK_BYTES = 64 * 1024;
    /** Compatibility alias for the largest supported dictionary. */
    public static final int MAX_BYTES = MAX_DOWNLINK_BYTES;
    private static final int ADAPTIVE_WINDOW = 128;
    private static final int ADAPTIVE_SAMPLE_INTERVAL = 64;

    private final byte[] bytes;
    private final long id;
    private static final Cleaner CLEANER = Cleaner.create();
    private final Prepared prepared;
    private final boolean[] recentDictionaryWins = new boolean[ADAPTIVE_WINDOW];
    private int recentOutcomeCount;
    private int recentDictionaryWinCount;
    private int recentOutcomeCursor;
    private int sampleCounter;

    private ZstdDictionary(byte[] bytes, long id) {
        this.bytes = bytes;
        this.id = id;
        this.prepared = new Prepared(bytes);
        CLEANER.register(this, prepared);
    }

    public static ZstdDictionary fromBytes(byte[] source) throws IOException {
        return fromBytes(source, MAX_DOWNLINK_BYTES);
    }

    public static ZstdDictionary fromBytes(byte[] source, int maxBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        if (maxBytes < MIN_BYTES || source.length < MIN_BYTES || source.length > maxBytes) {
            throw new IOException("dictionary size must be between " + MIN_BYTES + " and " + maxBytes + " bytes");
        }

        var bytes = Arrays.copyOf(source, source.length);
        long id;
        try {
            id = Zstd.getDictIdFromDict(bytes);
        } catch (RuntimeException e) {
            throw new IOException("could not read ZSTD dictionary id", e);
        }
        if (Zstd.isError(id) || id == 0L) {
            throw new IOException("invalid ZSTD dictionary");
        }
        // Reading the ID alone does not validate the entropy tables.
        try {
            var probe = new byte[1024];
            var compressed = Zstd.compressUsingDict(probe, bytes, 1);
            if (!Arrays.equals(probe, Zstd.decompress(compressed, bytes, probe.length))) {
                throw new IOException("dictionary round-trip validation failed");
            }
        } catch (RuntimeException e) {
            throw new IOException("invalid ZSTD dictionary tables", e);
        }
        return new ZstdDictionary(bytes, id);
    }

    public long id() {
        return id;
    }

    public int size() {
        return bytes.length;
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public synchronized boolean shouldPreferDictionary() {
        return recentOutcomeCount == ADAPTIVE_WINDOW
                && recentDictionaryWinCount * 100 >= ADAPTIVE_WINDOW * 95;
    }

    public synchronized boolean shouldSampleUncompressed() {
        sampleCounter = (sampleCounter + 1) % ADAPTIVE_SAMPLE_INTERVAL;
        return sampleCounter == 0;
    }

    public synchronized void recordCompressionOutcome(boolean dictionaryWon) {
        if (recentOutcomeCount == ADAPTIVE_WINDOW && recentDictionaryWins[recentOutcomeCursor]) {
            recentDictionaryWinCount--;
        } else if (recentOutcomeCount < ADAPTIVE_WINDOW) {
            recentOutcomeCount++;
        }
        recentDictionaryWins[recentOutcomeCursor] = dictionaryWon;
        if (dictionaryWon) recentDictionaryWinCount++;
        recentOutcomeCursor = (recentOutcomeCursor + 1) % ADAPTIVE_WINDOW;
    }

    public byte[] compress(byte[] raw, int level) {
        try {
            synchronized (prepared) {
                return Zstd.compress(raw, prepared.compressor(Math.clamp(level, 1, 22)));
            }
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public byte[] decompress(byte[] compressed, int rawLength) {
        try {
            return Zstd.decompress(compressed, prepared.decompressor, rawLength);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    // Immutable dictionaries and a bounded set of compressor contexts are shared per dictionary.
    // Cleanup happens only once no store or connection references this dictionary.
    private static final class Prepared implements Runnable {
        private static final int MAX_COMPRESSORS = 3;
        final LinkedHashMap<Integer, ZstdDictCompress> compressors = new LinkedHashMap<>(4, 0.75f, true);
        final byte[] dictionaryBytes;
        final ZstdDictDecompress decompressor;
        Prepared(byte[] bytes) {
            dictionaryBytes = bytes;
            decompressor = new ZstdDictDecompress(bytes);
        }
        ZstdDictCompress compressor(int level) {
            var existing = compressors.get(level);
            if (existing != null) return existing;
            var created = new ZstdDictCompress(dictionaryBytes, level);
            compressors.put(level, created);
            while (compressors.size() > MAX_COMPRESSORS) {
                var iterator = compressors.entrySet().iterator();
                var eldest = iterator.next();
                iterator.remove();
                eldest.getValue().close();
            }
            return created;
        }
        public void run() {
            synchronized (this) {
                compressors.values().forEach(ZstdDictCompress::close);
                compressors.clear();
                decompressor.close();
            }
        }
    }
}
