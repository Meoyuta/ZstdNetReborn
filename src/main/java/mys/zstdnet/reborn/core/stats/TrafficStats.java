package mys.zstdnet.reborn.core.stats;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TrafficStats {
    private static final long SAMPLE_INTERVAL_MS = 500L;

    private final AtomicInteger connections = new AtomicInteger();
    private final java.util.concurrent.ConcurrentHashMap<Long, AtomicInteger> dictionaryConnections =
        new java.util.concurrent.ConcurrentHashMap<>();

    public void addDictionaryConnection(long id, int delta) {
        dictionaryConnections.compute(id, (key, count) -> {
            if (count == null) count = new AtomicInteger();
            return count.addAndGet(delta) <= 0 ? null : count;
        });
    }

    public int dictionaryConnections() {
        return dictionaryConnections.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public int dictionaryConnections(long id) {
        AtomicInteger count = dictionaryConnections.get(id);
        return count == null ? 0 : count.get();
    }
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong rawUp = new AtomicLong();
    private final AtomicLong rawDown = new AtomicLong();
    private final AtomicLong wireUp = new AtomicLong();
    private final AtomicLong wireDown = new AtomicLong();

    private volatile long sampleAtMs = System.currentTimeMillis();
    private volatile long sampledRawUp;
    private volatile long sampledRawDown;
    private volatile long sampledWireUp;
    private volatile long sampledWireDown;
    private volatile long rawUpRate;
    private volatile long rawDownRate;
    private volatile long wireUpRate;
    private volatile long wireDownRate;

    public void addConnection(int delta) {
        if (delta > 0) {
            totalConnections.addAndGet(delta);
        }
        connections.addAndGet(delta);
    }

    public void addRawUp(long bytes) {
        if (bytes > 0) {
            rawUp.addAndGet(bytes);
        }
    }

    public void addRawDown(long bytes) {
        if (bytes > 0) {
            rawDown.addAndGet(bytes);
        }
    }

    public void addWireUp(long bytes) {
        if (bytes > 0) {
            wireUp.addAndGet(bytes);
        }
    }

    public void addWireDown(long bytes) {
        if (bytes > 0) {
            wireDown.addAndGet(bytes);
        }
    }

    public synchronized Snapshot snapshot() {
        long now = System.currentTimeMillis();
        long currentRawUp = rawUp.get();
        long currentRawDown = rawDown.get();
        long currentWireUp = wireUp.get();
        long currentWireDown = wireDown.get();
        long elapsed = now - sampleAtMs;
        if (elapsed >= SAMPLE_INTERVAL_MS) {
            rawUpRate = scale(currentRawUp - sampledRawUp, elapsed);
            rawDownRate = scale(currentRawDown - sampledRawDown, elapsed);
            wireUpRate = scale(currentWireUp - sampledWireUp, elapsed);
            wireDownRate = scale(currentWireDown - sampledWireDown, elapsed);
            sampledRawUp = currentRawUp;
            sampledRawDown = currentRawDown;
            sampledWireUp = currentWireUp;
            sampledWireDown = currentWireDown;
            sampleAtMs = now;
        }
        long rawTotal = currentRawUp + currentRawDown;
        long wireTotal = currentWireUp + currentWireDown;
        double ratio = rawTotal <= 0L ? 0.0D : (wireTotal * 100.0D / rawTotal);
        return new Snapshot(
            connections.get(),
            totalConnections.get(),
            currentRawUp,
            currentRawDown,
            currentWireUp,
            currentWireDown,
            rawUpRate,
            rawDownRate,
            wireUpRate,
            wireDownRate,
            ratio
        );
    }

    private static long scale(long delta, long elapsedMs) {
        if (delta <= 0L || elapsedMs <= 0L) {
            return 0L;
        }
        return Math.round(delta * (1000.0D / elapsedMs));
    }

    public record Snapshot(
        int connections,
        long totalConnections,
        long rawUpBytes,
        long rawDownBytes,
        long wireUpBytes,
        long wireDownBytes,
        long rawUpRate,
        long rawDownRate,
        long wireUpRate,
        long wireDownRate,
        double ratioPercent
    ) {
    }
}
