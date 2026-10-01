package mys.zstdnet.reborn.core.dictionary;

import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import com.github.luben.zstd.Zstd;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ZstdDictionaryTrainer implements AutoCloseable {
    public static final Duration DEFAULT_DURATION = Duration.ofMinutes(10);
    public static final int UPLINK_DICTIONARY_BYTES = 64 * 1024;
    public static final int DOWNLINK_DICTIONARY_BYTES = 128 * 1024;
    /** Compatibility alias for the default (downlink) trainer. */
    public static final int DICTIONARY_BYTES = DOWNLINK_DICTIONARY_BYTES;
    public static final int SAMPLE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_SAMPLE_BYTES = 64 * 1024;
    private final Object lock = new Object();
    private final ZstdDictionaryStore store;
    private final ZstdNetLogger logger;
    private final int dictionaryBytes;
    private final boolean bundleMode;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "zstdnet-dictionary-training");
        thread.setDaemon(true);
        return thread;
    });
    private Session session;
    private boolean closed;
    private boolean shuttingDown;
    private String result = "idle";

    public ZstdDictionaryTrainer(ZstdDictionaryStore store, ZstdNetLogger logger) {
        this(store, logger, DOWNLINK_DICTIONARY_BYTES, true);
    }

    public ZstdDictionaryTrainer(ZstdDictionaryStore store, ZstdNetLogger logger, int dictionaryBytes) {
        this(store, logger, dictionaryBytes, false);
    }

    public ZstdDictionaryTrainer(ZstdDictionaryStore store, ZstdNetLogger logger,
                                 int dictionaryBytes, boolean bundleMode) {
        this.store = store;
        this.logger = logger;
        if (dictionaryBytes < ZstdDictionary.MIN_BYTES || dictionaryBytes > DOWNLINK_DICTIONARY_BYTES) {
            throw new IllegalArgumentException("dictionaryBytes out of range");
        }
        this.dictionaryBytes = dictionaryBytes;
        this.bundleMode = bundleMode;
    }

    public boolean start(Duration duration, int compressionLevel) {
        Duration selected = duration == null ? DEFAULT_DURATION : duration;
        if (selected.isZero() || selected.isNegative() || selected.compareTo(Duration.ofDays(1)) > 0) return false;
        synchronized (lock) {
            if (closed || shuttingDown || session != null) return false;
            Session next = new Session(System.currentTimeMillis() + selected.toMillis(), compressionLevel);
            session = next;
            result = "collecting";
            next.task = executor.schedule(() -> finish(next), selected.toMillis(), TimeUnit.MILLISECONDS);
            logger.debug("Dictionary training started for " + selected.toSeconds() + " seconds");
            return true;
        }
    }

    public boolean stopAndFinalize() {
        synchronized (lock) {
            if (closed || shuttingDown || session == null || session.finalizing) return false;
            Session current = session;
            current.task.cancel(false);
            current.finalizing = true;
            executor.execute(() -> train(current));
            return true;
        }
    }

    public void capture(byte[] raw) {
        capture(false, raw);
    }

    /** Captures a sample for server outbound (downlink) or inbound (uplink) training. */
    public void capture(boolean uplink, byte[] raw) {
        if (raw == null || raw.length == 0) return;
        synchronized (lock) {
            if (closed || shuttingDown || session == null || session.finalizing) return;
            List<byte[]> samples = uplink ? session.uplinkSamples : session.downlinkSamples;
            int currentBytes = uplink ? session.uplinkBytes : session.downlinkBytes;
            int length = Math.min(raw.length, Math.min(MAX_SAMPLE_BYTES, SAMPLE_BYTES - currentBytes));
            if (length <= 0) return;
            samples.add(Arrays.copyOf(raw, length));
            if (uplink) session.uplinkBytes += length;
            else session.downlinkBytes += length;
            session.totalBytes += length;
            if (session.totalBytes >= SAMPLE_BYTES * 2) stopAndFinalize();
        }
    }

    public Status status() {
        synchronized (lock) {
            return session == null ? new Status(false, false, 0, 0, 0, result)
                : new Status(true, session.finalizing, Math.max(0, session.endsAt - System.currentTimeMillis()),
                    session.uplinkSamples.size() + session.downlinkSamples.size(), session.totalBytes, result);
        }
    }

    public void abort() {
        synchronized (lock) {
            if (session != null) {
                session.task.cancel(false);
                session = null;
                result = "cancelled";
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            abort();
            executor.shutdownNow();
        }
    }

    // Server shutdown waits for the existing training task instead of cancelling publication.
    public void finishAndClose() {
        synchronized (lock) {
            if (closed) return;
            shuttingDown = true;
            if (session != null && !session.finalizing) {
                Session current = session;
                current.task.cancel(false);
                current.finalizing = true;
                logger.debug("Dictionary shutdown: collection stopped at " + current.totalBytes
                    + "/" + (SAMPLE_BYTES * 2) + " bytes, "
                    + (current.uplinkSamples.size() + current.downlinkSamples.size()) + " samples");
                executor.execute(() -> train(current));
            }
            executor.shutdown();
        }
        boolean interrupted = false;
        for (;;) {
            try {
                if (executor.awaitTermination(5, TimeUnit.SECONDS)) break;
                logger.debug("Dictionary shutdown: training or saving is still in progress...");
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        synchronized (lock) {
            closed = true;
            logger.debug("Dictionary shutdown complete: " + result);
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void finish(Session expected) {
        synchronized (lock) {
            if (closed || session != expected || expected.finalizing) return;
            expected.finalizing = true;
        }
        train(expected);
    }

    private void train(Session expected) {
        try {
            if (expected.uplinkSamples.size() <= 10 || expected.downlinkSamples.size() <= 10
                    || expected.uplinkBytes < 4096 || expected.downlinkBytes < 4096) {
                throw new IllegalStateException("not enough traffic samples; connect players and try again");
            }
            logger.debug("Dictionary training: uplink " + expected.uplinkSamples.size() + " samples/"
                + expected.uplinkBytes + " bytes, downlink " + expected.downlinkSamples.size() + " samples/"
                + expected.downlinkBytes + " bytes; target dictionaries "
                + DICTIONARY_BYTES + " bytes");
            byte[] uplink;
            byte[] downlink;
            if (bundleMode) {
                uplink = train(expected.uplinkSamples, UPLINK_DICTIONARY_BYTES, expected.level);
                downlink = train(expected.downlinkSamples, DOWNLINK_DICTIONARY_BYTES, expected.level);
            } else {
                downlink = train(expected.downlinkSamples, dictionaryBytes, expected.level);
                uplink = downlink;
            }
            synchronized (lock) {
                // Native training cannot be interrupted safely. Cancel its publication on stop/import.
                if (closed || session != expected) return;
                logger.debug("Dictionary training complete; validating and saving directional bundle"
                    + " bytes to " + store.dictionaryPath());
                ZstdDictionary dictionary = bundleMode
                    ? store.saveBundle(uplink, downlink)
                    : store.save(uplink, dictionaryBytes);
                result = "saved dictionary " + Long.toUnsignedString(dictionary.id());
                session = null;
                logger.debug(result + "; new connections will synchronize it");
            }
        } catch (Exception e) {
            synchronized (lock) {
                if (closed || session != expected) return;
                result = "failed: " + e.getMessage();
                session = null;
                logger.warn("Dictionary training " + result);
            }
        }
    }

    private static byte[] train(List<byte[]> samples, int capacity, int level) {
        byte[] buffer = new byte[capacity];
        long trained = Zstd.trainFromBuffer(samples.toArray(byte[][]::new), buffer, false, level);
        if (Zstd.isError(trained) || trained <= 0 || trained > buffer.length) {
            throw new IllegalStateException(Zstd.getErrorName(trained));
        }
        return Arrays.copyOf(buffer, (int) trained);
    }

    public record Status(boolean training, boolean finalizing, long remainingMillis,
                         int sampleCount, int sampleBytes, String result) {}
    private static final class Session {
        final long endsAt;
        final int level;
        final List<byte[]> uplinkSamples = new ArrayList<>();
        final List<byte[]> downlinkSamples = new ArrayList<>();
        int uplinkBytes;
        int downlinkBytes;
        int totalBytes;
        boolean finalizing;
        ScheduledFuture<?> task;
        Session(long endsAt, int level) { this.endsAt = endsAt; this.level = level; }
    }
}
