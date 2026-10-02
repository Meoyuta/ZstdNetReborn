package mys.zstdnet.reborn.core.protocol;

import com.github.luben.zstd.Zstd;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class ZstdFrameCodec {
    public static final byte[] MAGIC = new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD};
    public static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private ZstdFrameCodec() {
    }

    public static byte[] compressFrame(byte[] raw, int level) throws IOException {
        return compressFrame(raw, level, null);
    }

    public static byte[] compressFrame(byte[] raw, int level, ZstdDictionary dictionary) throws IOException {
        return compressFrame(raw, level, dictionary, true);
    }

    /** Compresses a frame while optionally leaving dictionary adaptation untouched. */
    public static byte[] compressFrame(byte[] raw, int level, ZstdDictionary dictionary,
                                       boolean recordAdaptiveOutcome) throws IOException {
        if (raw.length < 32) {
            var out = new ByteArrayOutputStream(raw.length + 10);
            out.write(VarIntCodec.encode(raw.length));
            out.write(VarIntCodec.encode(0));
            out.write(raw);
            return out.toByteArray();
        }
        var compareWithoutDictionary = dictionary == null
                || !dictionary.shouldPreferDictionary()
                || dictionary.shouldSampleUncompressed();
        var compressed = compareWithoutDictionary ? Zstd.compress(raw, level) : null;
        var usesDictionary = false;
        if (dictionary != null) {
            var candidate = dictionary.compress(raw, level);
            if (compressed == null || candidate.length < compressed.length) {
                compressed = candidate;
                usesDictionary = true;
            }
            if (compareWithoutDictionary && recordAdaptiveOutcome) dictionary.recordCompressionOutcome(usesDictionary);
        }
        var storedTag = (compressed.length << 1) | (usesDictionary ? 1 : 0);
        var storeRaw = compressed.length + VarIntCodec.encode(storedTag).length >= raw.length + 1;
        var out = new ByteArrayOutputStream(Math.min(raw.length, compressed.length) + 10);
        out.write(VarIntCodec.encode(raw.length));
        if (storeRaw) {
            out.write(VarIntCodec.encode(0));
            out.write(raw);
        } else {
            out.write(VarIntCodec.encode(storedTag));
            out.write(compressed);
        }
        return out.toByteArray();
    }

    public static byte[] readFrame(InputStream in) throws IOException {
        return readFrame(in, null);
    }

    public static byte[] readFrame(InputStream in, ZstdDictionary dictionary) throws IOException {
        var rawLength = VarIntCodec.read(in);
        var storedTag = VarIntCodec.read(in);
        if (rawLength <= 0 || rawLength > MAX_FRAME_BYTES || storedTag < 0 || storedTag > (MAX_FRAME_BYTES << 1) + 1) {
            throw new IOException("invalid zstd frame length");
        }
        if (storedTag == 0) {
            return PacketIo.readFully(in, rawLength);
        }
        var usesDictionary = (storedTag & 1) == 1;
        var storedLength = storedTag >>> 1;
        if (storedLength == 0) {
            throw new IOException("invalid zstd frame payload length");
        }
        var compressed = PacketIo.readFully(in, storedLength);
        if (usesDictionary && dictionary == null) {
            throw new IOException("received dictionary-compressed ZstdNet frame before dictionary activation");
        }
        return decompressFrame(compressed, rawLength, usesDictionary ? dictionary : null);
    }

    public static byte[] decompressFrame(byte[] compressed, int rawLength) throws IOException {
        return decompressFrame(compressed, rawLength, null);
    }

    public static byte[] decompressFrame(byte[] compressed, int rawLength, ZstdDictionary dictionary) throws IOException {
        var raw = dictionary == null ? Zstd.decompress(compressed, rawLength) : dictionary.decompress(compressed, rawLength);
        if (raw.length != rawLength) {
            throw new IOException("zstd frame length mismatch");
        }
        return raw;
    }
}
