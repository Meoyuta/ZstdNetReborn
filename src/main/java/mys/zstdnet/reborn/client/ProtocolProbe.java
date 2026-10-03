package mys.zstdnet.reborn.client;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

final class ProtocolProbe {
    static final int TIMEOUT_MILLIS = 3_000;
    private static final long SUCCESS_TTL_MILLIS = 5 * 60_000L;
    private static final long FAILURE_TTL_MILLIS = 30_000L;
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(2, 4, 30L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64), task -> {
        var thread = new Thread(task, "zstdnet-protocol-probe-" + THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<ProbeResult>> IN_FLIGHT = new ConcurrentHashMap<>();

    private ProtocolProbe() {}

    static ProbeResult cached(String host, int port) {
        var value = CACHE.get(key(host, port));
        return value == null || value.expiresAt < System.currentTimeMillis() ? null : value.result;
    }

    static void start(String host, int port) {
        request(host, port);
    }

    static CompletableFuture<ProbeResult> probeAsync(String host, int port) {
        return request(host, port);
    }

    static void clearForTests() {
        CACHE.clear();
        IN_FLIGHT.clear();
    }

    static ProbeResult awaitResult(String host, int port, long timeoutMillis) {
        var cached = cached(host, port);
        if (cached != null) return cached;
        try {
            return request(host, port).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return ProbeResult.unsupported();
        }
    }

    private static CompletableFuture<ProbeResult> request(String host, int port) {
        var cacheKey = key(host, port);
        var cached = cached(host, port);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return IN_FLIGHT.computeIfAbsent(cacheKey, ignored -> {
            CompletableFuture<ProbeResult> future;
            try {
                future = CompletableFuture.supplyAsync(() -> probe(host, port), EXECUTOR)
                    .orTimeout(TIMEOUT_MILLIS + 500L, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> ProbeResult.unsupported());
            } catch (RejectedExecutionException rejected) {
                future = CompletableFuture.completedFuture(ProbeResult.unsupported());
            }
            return future.whenComplete((supported, error) -> {
                var result = error == null && supported != null ? supported : ProbeResult.unsupported();
                CACHE.put(cacheKey, new Cached(result, System.currentTimeMillis()
                    + (result.supported() ? SUCCESS_TTL_MILLIS : FAILURE_TTL_MILLIS)));
                IN_FLIGHT.remove(cacheKey);
            });
        });
    }

    private static ProbeResult probe(String host, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream output = socket.getOutputStream();
            output.write(ZstdFrameCodec.PROTOCOL_PROBE_MAGIC);
            output.flush();
            InputStream input = socket.getInputStream();
            byte[] response = input.readNBytes(ZstdFrameCodec.PROTOCOL_PROBE_RESPONSE_BYTES);
            if (response.length != ZstdFrameCodec.PROTOCOL_PROBE_RESPONSE_BYTES) return ProbeResult.unsupported();
            for (int i = 0; i < ZstdFrameCodec.PROTOCOL_PROBE_RESPONSE_PREFIX.length; i++) {
                if (response[i] != ZstdFrameCodec.PROTOCOL_PROBE_RESPONSE_PREFIX[i]) return ProbeResult.unsupported();
            }
            int level = response[ZstdFrameCodec.PROTOCOL_PROBE_RESPONSE_BYTES - 1] & 0xFF;
            if (level < 1 || level > 22) return ProbeResult.unsupported();
            return new ProbeResult(true, level);
        } catch (Exception ignored) {
            return ProbeResult.unsupported();
        }
    }

    private static String key(String host, int port) {
        return normalizeHost(host) + ":" + port;
    }

    static String normalizeHost(String host) {
        if (host == null) return "";
        var value = host.trim();
        if (value.length() > 1 && value.charAt(0) == '[' && value.charAt(value.length() - 1) == ']') {
            value = value.substring(1, value.length() - 1);
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    record ProbeResult(boolean supported, int serverLevel) {
        static ProbeResult unsupported() {
            return new ProbeResult(false, -1);
        }
    }

    private record Cached(ProbeResult result, long expiresAt) {}
}
