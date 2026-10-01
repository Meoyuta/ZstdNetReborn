package mys.zstdnet.reborn.core.protocol;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Stateful v2 stream codec. A flush preserves the zstd window between records. */
public final class ZstdPersistentStreamCodec implements AutoCloseable {
    private final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    private final GrowingInputStream input = new GrowingInputStream();
    private final ZstdOutputStreamNoFinalizer output;
    private final ZstdInputStreamNoFinalizer decoder;
    private int emitted;
    private boolean closed;

    public ZstdPersistentStreamCodec(int level, ZstdDictionary dictionary) throws IOException {
        output = new ZstdOutputStreamNoFinalizer(encoded)
                .setLevel(Math.clamp(level, 1, 22))
                .setCloseFrameOnFlush(false);
        decoder = new ZstdInputStreamNoFinalizer(input);
        decoder.setContinuous(true);
        if (dictionary != null) {
            output.setDict(dictionary.bytes());
            decoder.setDict(dictionary.bytes());
        }
    }

    public synchronized byte[] compress(byte[] raw) throws IOException {
        ensureOpen();
        if (raw == null || raw.length == 0 || raw.length > ZstdFrameCodec.MAX_FRAME_BYTES) {
            throw new IOException("invalid persistent stream packet length");
        }
        output.write(raw);
        output.flush();
        var all = encoded.toByteArray();
        var delta = Arrays.copyOfRange(all, emitted, all.length);
        emitted = all.length;
        return delta;
    }

    public synchronized byte[] decompress(byte[] payload, int rawLength) throws IOException {
        ensureOpen();
        if (rawLength <= 0 || rawLength > ZstdFrameCodec.MAX_FRAME_BYTES) {
            throw new IOException("invalid persistent stream raw length");
        }
        input.append(payload);
        var raw = decoder.readNBytes(rawLength);
        if (raw.length != rawLength) throw new IOException("persistent stream ended before packet boundary");
        return raw;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("persistent stream codec is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            output.close();
            input.closeInput();
            decoder.close();
        }
    }

    private static final class GrowingInputStream extends InputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private byte[] readable = new byte[0];
        private int position;
        private boolean closed;

        synchronized void append(byte[] bytes) throws IOException {
            if (closed) throw new IOException("persistent stream input is closed");
            if (bytes.length == 0) return;
            var remaining = Arrays.copyOfRange(readable, position, readable.length);
            pending.reset();
            pending.write(remaining);
            pending.write(bytes);
            readable = pending.toByteArray();
            position = 0;
        }

        synchronized void closeInput() {
            closed = true;
        }

        @Override
        public synchronized int read() {
            if (position >= readable.length) return closed ? -1 : 0;
            return readable[position++] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            if (position >= readable.length) return closed ? -1 : 0;
            int count = Math.min(length, readable.length - position);
            System.arraycopy(readable, position, bytes, offset, count);
            position += count;
            return count;
        }
    }
}
