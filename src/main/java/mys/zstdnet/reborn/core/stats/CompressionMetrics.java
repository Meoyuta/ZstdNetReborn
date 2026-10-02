package mys.zstdnet.reborn.core.stats;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class CompressionMetrics {
    private final LongAdder frames = new LongAdder();
    private final LongAdder batches = new LongAdder();
    private final LongAdder syncMicros = new LongAdder();
    private final LongAdder asyncMicros = new LongAdder();
    private final LongAdder degraded = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final AtomicLong maxFrameMicros = new AtomicLong();
    private final LongAdder[] sizeHistogram = {
        new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(),
        new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()
    };

    public void recordSync(int rawBytes, int batchPackets, long elapsedNanos) {
        frames.increment();
        if (batchPackets > 1) batches.increment();
        long micros = Math.max(0L, elapsedNanos / 1_000L);
        syncMicros.add(micros);
        maxFrameMicros.accumulateAndGet(micros, Math::max);
        sizeHistogram[bucket(rawBytes)].increment();
    }

    public void recordAsync(long elapsedNanos) {
        asyncMicros.add(Math.max(0L, elapsedNanos / 1_000L));
    }

    public void recordAsync(int rawBytes, int batchPackets, long elapsedNanos) {
        frames.increment();
        if (batchPackets > 1) batches.increment();
        asyncMicros.add(Math.max(0L, elapsedNanos / 1_000L));
        maxFrameMicros.accumulateAndGet(Math.max(0L, elapsedNanos / 1_000L), Math::max);
        sizeHistogram[bucket(rawBytes)].increment();
    }

    public void recordDegraded() {
        degraded.increment();
    }

    public void recordDropped() {
        dropped.increment();
    }

    private static int bucket(int bytes) {
        if (bytes < 1 << 10) return 0;
        if (bytes < 1 << 12) return 1;
        if (bytes < 1 << 14) return 2;
        if (bytes < 1 << 16) return 3;
        if (bytes < 1 << 18) return 4;
        if (bytes < 1 << 20) return 5;
        if (bytes < 1 << 21) return 6;
        return 7;
    }

    public Snapshot snapshot() {
        long[] histogram = new long[sizeHistogram.length];
        for (int i = 0; i < histogram.length; i++) histogram[i] = sizeHistogram[i].sum();
        return new Snapshot(frames.sum(), batches.sum(), syncMicros.sum(), asyncMicros.sum(),
            degraded.sum(), dropped.sum(), maxFrameMicros.get(), histogram);
    }

    public record Snapshot(long frames, long batches, long syncMicros, long asyncMicros,
                           long degraded, long dropped, long maxFrameMicros, long[] sizeHistogram) {}
}
