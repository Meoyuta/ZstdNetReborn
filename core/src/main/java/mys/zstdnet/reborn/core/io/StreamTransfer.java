package mys.zstdnet.reborn.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;

public final class StreamTransfer {
    private static final int BUFFER_SIZE = 16 * 1024;

    private StreamTransfer() {
    }

    public static void copyAndFlush(InputStream in, OutputStream out) throws IOException {
        copyAndFlush(in, out, Duration.ZERO);
    }

    public static void copyAndFlush(InputStream in, OutputStream out, Duration flushInterval) throws IOException {
        var buffer = new byte[BUFFER_SIZE];
        var lastFlush = System.nanoTime();
        var intervalNanos = flushInterval == null ? 0L : flushInterval.toNanos();
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            out.write(buffer, 0, read);
            var now = System.nanoTime();
            if (intervalNanos <= 0L || now - lastFlush >= intervalNanos) {
                out.flush();
                lastFlush = now;
            }
        }
        out.flush();
    }
}
