package mys.zstdnet.reborn.core.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public final class VarIntCodec {
    private static final int MAX_VARINT_BYTES = 5;

    private VarIntCodec() {
    }

    public static byte[] encode(int value) {
        var out = new byte[MAX_VARINT_BYTES];
        var index = 0;
        var remaining = value;
        do {
            var next = (byte) (remaining & 0x7F);
            remaining >>>= 7;
            if (remaining != 0) {
                next |= (byte) 0x80;
            }
            out[index++] = next;
        } while (remaining != 0);

        var exact = new byte[index];
        System.arraycopy(out, 0, exact, 0, index);
        return exact;
    }

    public static int read(InputStream in) throws IOException {
        var value = 0;
        var shift = 0;
        for (int i = 0; i < MAX_VARINT_BYTES; i++) {
            var b = in.read();
            if (b < 0) {
                throw new EOFException("unexpected eof while reading varint");
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("varint too large");
    }

    public static VarIntRead read(byte[] data, int offset) {
        return read(data, offset, data.length);
    }

    public static VarIntRead read(byte[] data, int offset, int limit) {
        var value = 0;
        var shift = 0;
        var max = Math.min(limit, offset + MAX_VARINT_BYTES);
        for (int i = offset; i < max; i++) {
            var b = data[i] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new VarIntRead(value, i + 1);
            }
            shift += 7;
        }
        return null;
    }
}
