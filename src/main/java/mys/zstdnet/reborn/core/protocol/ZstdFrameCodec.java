package mys.zstdnet.reborn.core.protocol;

import io.netty.buffer.ByteBuf;

import java.io.IOException;

/**
 * Frame headers and streaming frame orchestration.
 *
 * <p>Compression state always belongs to a {@link ZstdPersistentStreamCodec}.
 * Every frame path, including offline benchmark, dictionary validation and
 * tests, writes to a caller-owned {@link ByteBuf}; there is no byte-array
 * one-shot compression adapter.</p>
 */
public final class ZstdFrameCodec {
    public static final byte[] MAGIC = new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD};
    /** Maximum declared raw payload accepted from the wire. */
    public static final int MAX_FRAME_BYTES = (2 * 1024 * 1024) + (64 * 1024);

    private ZstdFrameCodec() {
    }

    /**
     * Encodes one logical record into a wire frame using the supplied stream.
     * The stream must be reused for subsequent records on the same direction.
     */
    public static void writeFrame(
            ByteBuf raw, ZstdPersistentStreamCodec codec,
            boolean usesDictionary, ByteBuf out
    ) throws IOException {
        if (raw == null || codec == null || out == null || !raw.isReadable()
                || raw.readableBytes() > MAX_FRAME_BYTES) {
            throw new IOException("invalid zstd frame length");
        }
        var rawLength = raw.readableBytes();
        if (rawLength < 32) {
            writeVarInt(out, rawLength);
            writeVarInt(out, 0);
            out.writeBytes(raw, raw.readerIndex(), rawLength);
            return;
        }
        var payload = out.alloc().buffer(Math.clamp(rawLength / 2, 256, 64 * 1024));
        try {
            codec.compress(raw, payload);
            var payloadLength = payload.readableBytes();
            writeVarInt(out, rawLength);
            writeVarInt(out, (payloadLength << 1) | (usesDictionary ? 1 : 0));
            out.writeBytes(payload);
        } finally {
            payload.release();
        }
    }

    /**
     * Decodes one frame from a ByteBuf using the supplied persistent stream.
     */
    public static int readFrame(ByteBuf in, ZstdPersistentStreamCodec codec,
                                ByteBuf out) throws IOException {
        var start = in.readerIndex();
        var rawLength = readVarInt(in);
        var storedTag = readVarInt(in);
        if (rawLength <= 0 || rawLength > MAX_FRAME_BYTES
                || storedTag < 0 || storedTag > (MAX_FRAME_BYTES << 1) + 1) {
            in.readerIndex(start);
            throw new IOException("invalid zstd frame length");
        }
        var payloadLength = storedTag == 0 ? rawLength : storedTag >>> 1;
        if (payloadLength <= 0 || in.readableBytes() < payloadLength) {
            in.readerIndex(start);
            return 0;
        }
        if (storedTag == 0) {
            out.writeBytes(in, payloadLength);
        } else {
            var payload = in.readRetainedSlice(payloadLength);
            try {
                codec.decompress(payload, rawLength, out);
            } finally {
                payload.release();
            }
        }
        return in.readerIndex() - start;
    }

    static void writeVarInt(ByteBuf out, int value) {
        var remaining = value;
        do {
            var next = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) next |= 0x80;
            out.writeByte(next);
        } while (remaining != 0);
    }

    static int readVarInt(ByteBuf in) throws IOException {
        var value = 0;
        var shift = 0;
        for (var i = 0; i < 5; i++) {
            if (!in.isReadable()) throw new IOException("incomplete zstd frame header");
            var next = in.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) return value;
            shift += 7;
        }
        throw new IOException("zstd frame varint too large");
    }
}
