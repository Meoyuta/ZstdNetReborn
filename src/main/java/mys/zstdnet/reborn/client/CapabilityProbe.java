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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class CapabilityProbe {
    private static final int TIMEOUT_MILLIS = 1_000;
    private static final long SUCCESS_TTL_MILLIS = 5 * 60_000L;
    private static final long FAILURE_TTL_MILLIS = 30_000L;
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        var thread = new Thread(r, "zstdnet-capability-probe");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Boolean>> IN_FLIGHT = new ConcurrentHashMap<>();

    private CapabilityProbe() {}

    static Boolean cached(String host, int port) {
        var value = CACHE.get(key(host, port));
        return value == null || value.expiresAt < System.currentTimeMillis() ? null : value.supported;
    }

    static void start(String host, int port) {
        var cacheKey = key(host, port);
        if (cached(host, port) != null) return;
        IN_FLIGHT.computeIfAbsent(cacheKey, ignored -> CompletableFuture
            .supplyAsync(() -> probe(host, port), EXECUTOR)
            .orTimeout(TIMEOUT_MILLIS + 250L, TimeUnit.MILLISECONDS)
            .exceptionally(error -> false)
            .whenComplete((supported, error) -> {
                boolean result = error == null && Boolean.TRUE.equals(supported);
                CACHE.put(cacheKey, new Cached(result, System.currentTimeMillis()
                    + (result ? SUCCESS_TTL_MILLIS : FAILURE_TTL_MILLIS)));
                IN_FLIGHT.remove(cacheKey);
            }));
    }

    private static boolean probe(String host, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream output = socket.getOutputStream();
            output.write(ZstdFrameCodec.CAPABILITY_MAGIC);
            output.flush();
            InputStream input = socket.getInputStream();
            for (byte expected : ZstdFrameCodec.CAPABILITY_RESPONSE) {
                if (input.read() != (expected & 0xFF)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String key(String host, int port) {
        return host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    private record Cached(boolean supported, long expiresAt) {}
}
