package mys.zstdnet.reborn.core.dictionary;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import mys.zstdnet.reborn.core.protocol.ZstdPersistentStreamCodec;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** Immutable validated dictionary shared by persistent stream codecs. */
public final class ZstdDictionary {
    public static final int MIN_BYTES = 256;
    public static final int MAX_DOWNLINK_BYTES = 128 * 1024;
    public static final int MAX_UPLINK_BYTES = 64 * 1024;
    public static final int MAX_BYTES = MAX_DOWNLINK_BYTES;
    private final byte[] bytes;
    private final long id;

    private ZstdDictionary(byte[] bytes, long id) {
        this.bytes = bytes;
        this.id = id;
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
        final long id;
        try {
            id = Zstd.getDictIdFromDict(bytes);
        } catch (RuntimeException e) {
            throw new IOException("could not read ZSTD dictionary id", e);
        }
        if (Zstd.isError(id) || id == 0L) {
            throw new IOException("invalid ZSTD dictionary");
        }
        var dictionary = new ZstdDictionary(bytes, id);
        validateWithPersistentStream(dictionary);
        return dictionary;
    }

    private static void validateWithPersistentStream(ZstdDictionary dictionary) throws IOException {
        var probe = new byte[1024];
        var input = Unpooled.wrappedBuffer(probe);
        var compressed = Unpooled.buffer();
        var decoded = Unpooled.buffer(probe.length);
        try (var encoder = new ZstdPersistentStreamCodec(1, dictionary);
             var decoder = new ZstdPersistentStreamCodec(1, dictionary)) {
            encoder.compress(input, compressed);
            decoder.decompress(compressed, probe.length, decoded);
            if (decoded.readableBytes() != probe.length) {
                throw new IOException("dictionary round-trip validation failed");
            }
            for (var i = 0; i < probe.length; i++) {
                if (decoded.getByte(decoded.readerIndex() + i) != probe[i]) {
                    throw new IOException("dictionary round-trip validation failed");
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("invalid ZSTD dictionary tables", e);
        } finally {
            input.release();
            compressed.release();
            decoded.release();
        }
    }

    public long id() {
        return id;
    }

    public int size() {
        return bytes.length;
    }

    /**
     * Returns immutable dictionary storage used by stream construction.
     * Callers must not modify the returned array.
     */
    public byte[] bytes() {
        return bytes;
    }

}
