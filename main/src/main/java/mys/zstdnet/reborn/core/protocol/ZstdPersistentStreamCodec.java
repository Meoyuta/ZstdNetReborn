package mys.zstdnet.reborn.core.protocol;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Stateful v2 stream codec. A flush preserves the zstd window between records. */
public final class ZstdPersistentStreamCodec implements AutoCloseable {
    /**
     * Captures only the bytes emitted by the current flush. Keeping the full
     * compressed history here makes every packet copy the entire connection
     * stream and retains an ever-growing heap buffer.
     */
    private final ByteBufOutputStream encoded = new ByteBufOutputStream();
    private final GrowingInputStream input = new GrowingInputStream();
    private final ZstdOutputStreamNoFinalizer output;
    private final ZstdInputStreamNoFinalizer decoder;
    private final byte[] transfer = new byte[8192];
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
        if (raw == null) throw new IOException("invalid persistent stream packet length");
        var input = Unpooled.wrappedBuffer(raw);
        var result = Unpooled.buffer();
        try {
            compress(input, result);
            var bytes = new byte[result.readableBytes()];
            result.readBytes(bytes);
            return bytes;
        } finally {
            input.release();
            result.release();
        }
    }

    public synchronized int compress(ByteBuf raw, ByteBuf out) throws IOException {
        ensureOpen();
        if (raw == null || raw.readableBytes() == 0 || raw.readableBytes() > ZstdFrameCodec.MAX_FRAME_BYTES) {
            throw new IOException("invalid persistent stream packet length");
        }
        encoded.attach(out);
        int before = out.writerIndex();
        int index = raw.readerIndex();
        int remaining = raw.readableBytes();
        if (raw.hasArray()) {
            output.write(raw.array(), raw.arrayOffset() + index, remaining);
        } else {
            while (remaining > 0) {
                int count = Math.min(remaining, transfer.length);
                raw.getBytes(index, transfer, 0, count);
                output.write(transfer, 0, count);
                index += count;
                remaining -= count;
            }
        }
        try {
            output.flush();
            return out.writerIndex() - before;
        } finally {
            encoded.detach();
        }
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

    public synchronized void decompress(ByteBuf payload, int rawLength, ByteBuf out) throws IOException {
        ensureOpen();
        if (rawLength <= 0 || rawLength > ZstdFrameCodec.MAX_FRAME_BYTES) {
            throw new IOException("invalid persistent stream raw length");
        }
        input.append(payload);
        int remaining = rawLength;
        while (remaining > 0) {
            int count = decoder.read(transfer, 0, Math.min(remaining, transfer.length));
            if (count < 0) throw new IOException("persistent stream ended before packet boundary");
            if (count == 0) continue;
            out.writeBytes(transfer, 0, count);
            remaining -= count;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("persistent stream codec is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            var trailer = Unpooled.buffer();
            encoded.attach(trailer);
            try {
                output.close();
            } finally {
                encoded.detach();
                trailer.release();
            }
            input.closeInput();
            decoder.close();
        }
    }

    private static final class GrowingInputStream extends InputStream {
        private byte[] readable = new byte[8192];
        private int position;
        private int limit;
        private boolean closed;

        synchronized void append(byte[] bytes) throws IOException {
            if (closed) throw new IOException("persistent stream input is closed");
            if (bytes.length == 0) return;
            compactIfNeeded(bytes.length);
            ensureCapacity(bytes.length);
            System.arraycopy(bytes, 0, readable, limit, bytes.length);
            limit += bytes.length;
        }

        synchronized void append(ByteBuf bytes) throws IOException {
            if (closed) throw new IOException("persistent stream input is closed");
            int index = bytes.readerIndex();
            int remaining = bytes.readableBytes();
            compactIfNeeded(remaining);
            while (remaining > 0) {
                int count = Math.min(remaining, 8192);
                ensureCapacity(count);
                bytes.getBytes(index, readable, limit, count);
                index += count;
                limit += count;
                remaining -= count;
            }
        }

        synchronized void closeInput() {
            closed = true;
        }

        @Override
        public synchronized int read() {
            if (position >= limit) return closed ? -1 : 0;
            return readable[position++] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            if (length == 0) return 0;
            if (position >= limit) return closed ? -1 : 0;
            int count = Math.min(length, limit - position);
            System.arraycopy(readable, position, bytes, offset, count);
            position += count;
            return count;
        }

        private void compactIfNeeded(int incoming) {
            if (position == 0) return;
            int remaining = limit - position;
            if (readable.length - limit < incoming) {
                System.arraycopy(readable, position, readable, 0, remaining);
                position = 0;
                limit = remaining;
            }
        }

        private void ensureCapacity(int incoming) {
            if (readable.length - limit >= incoming) return;
            int required = limit + incoming;
            int capacity = readable.length;
            while (capacity < required) capacity = Math.max(capacity << 1, required);
            var expanded = new byte[capacity];
            System.arraycopy(readable, position, expanded, 0, limit - position);
            limit -= position;
            position = 0;
            readable = expanded;
        }
    }

    private static final class ByteBufOutputStream extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream(32);
        private ByteBuf target;

        void attach(ByteBuf target) {
            this.target = target;
            if (pending.size() > 0) {
                target.writeBytes(pending.toByteArray());
                pending.reset();
            }
        }

        void detach() {
            target = null;
        }

        @Override
        public void write(int value) {
            if (target == null) pending.write(value);
            else target.writeByte(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (target == null) pending.write(bytes, offset, length);
            else target.writeBytes(bytes, offset, length);
        }

    }
}
