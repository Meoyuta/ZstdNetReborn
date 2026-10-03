package mys.zstdnet.reborn.client;

import mys.zstdnet.reborn.core.netty.ZstdFrameStats;
import mys.zstdnet.reborn.core.netty.ZstdDictionarySession;
import mys.zstdnet.reborn.core.netty.ZstdNettyPipeline;
import mys.zstdnet.reborn.neoforge.helper.SableCompat;
import mys.zstdnet.reborn.neoforge.network.ZstdState;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.AttributeKey;

import java.util.Locale;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;

public final class ZstdNetConnectionHooks {
    static final long PROBE_WAIT_MILLIS = ProtocolProbe.TIMEOUT_MILLIS + 500L;
    private static final long PENDING_CONNECT_TTL_MS = 15_000L;
    private static final Map<String, ConcurrentLinkedQueue<PendingConnection>> PENDING = new ConcurrentHashMap<>();
    private static final int MAX_PENDING_PER_KEY = 8;
    private static final AttributeKey<Long> RETRY_DEADLINE = AttributeKey.valueOf("zstdnet-install-deadline");
    private static volatile ZstdState lastState = ZstdState.SERVER_DISABLED;

    private ZstdNetConnectionHooks() {
    }

    public static boolean prepare(String host, int port) {
        expireStale();
        if (host == null || host.isBlank()) {
            ZstdNetClient.logger().debug("prepare skipped: blank host");
            return false;
        }

        var protocol = ProtocolProbe.cached(host, port);
        if (protocol != null) {
            return protocol.supported() && protocol.serverLevel() >= 1
                && enqueuePending(host, port, protocol);
        }
        ZstdNetClient.logger().info("Negotiating ZstdNet compression protocol for " + host + ":" + port);
        ProtocolProbe.probeAsync(host, port).thenAccept(result -> {
            if (result != null && result.supported() && result.serverLevel() >= 1) {
                enqueuePending(host, port, result);
                return;
            }
            lastState = ZstdState.PROBE_FAILED;
            ZstdNetClient.logger().info("ZstdNet protocol probe failed or timed out for "
                + host + ":" + port + "; using the ordinary protocol (no compression for this connection)");
            ZstdNetClient.logger().warn("ZstdNet protocol probe failed or timed out; connection remains uncompressed for "
                + host + ":" + port);
        });
        return false;
    }

    public static ZstdState lastState() {
        return lastState;
    }

    public static void install(ChannelPipeline pipeline) {
        install(pipeline, false);
    }

    private static void install(ChannelPipeline pipeline, boolean retry) {
        ZstdNetClient.logger().debug("install requested: channel="
            + (pipeline == null ? "null" : pipeline.channel().getClass().getName())
            + ", names=" + (pipeline == null ? "[]" : pipeline.names()));
        if (SableCompat.isSableUdpPipeline(pipeline)) {
            ZstdNetClient.logger().debug("install skipped: Sable UDP channel is passthrough");
            return;
        }
        if (isDatagramPipeline(pipeline)) {
            ZstdNetClient.logger().debug("install skipped: non-Sable DatagramChannel pipeline is passthrough, names="
                + (pipeline != null ? pipeline.names() : "null"));
            return;
        }
        if (!pipeline.channel().isActive() || !(pipeline.channel().remoteAddress() instanceof InetSocketAddress address)) {
            ZstdNetClient.logger().warn("install deferred: TCP remote address unavailable, open="
                + pipeline.channel().isOpen() + ", active=" + pipeline.channel().isActive());
            return;
        }
        if (pipeline.get(ZstdNettyPipeline.INBOUND_HANDLER) != null) {
            ZstdNetClient.logger().debug("install skipped: ZstdNet pipeline already installed");
            return;
        }
        var pending = pollPendingByPort(address.getPort());
        if (pending == null) {
            if (!retry) {
                var deadline = System.currentTimeMillis() + PROBE_WAIT_MILLIS;
                pipeline.channel().attr(RETRY_DEADLINE).set(deadline);
                scheduleInstallRetry(pipeline, deadline);
            } else {
                var deadline = pipeline.channel().attr(RETRY_DEADLINE).get();
                if (deadline != null && System.currentTimeMillis() < deadline) {
                    scheduleInstallRetry(pipeline, deadline);
                    return;
                }
                pipeline.channel().attr(RETRY_DEADLINE).set(null);
            }
            ZstdNetClient.logger().info("install skipped: no live pending TCP connection for port " + address.getPort());
            return;
        }

        ZstdNettyPipeline.install(
            pipeline,
            pending.compressionLevel(),
            true,
            ZstdFrameStats.NONE,
            ZstdDictionarySession.client(ZstdNetClient::receiveServerDictionary,
                ZstdNetClient.dictionaryDownloadListener(), ZstdNetClient.uplinkDictionary(),
                ZstdNetClient.downlinkDictionary())
        );
        ZstdNetClient.logger().info("installed ZstdNet pipeline for " + pending.host() + ":" + pending.port());
        ZstdNetClient.logger().debug("TCP pipeline installed: " + pipeline.names());
        pipeline.channel().attr(RETRY_DEADLINE).set(null);
    }

    private static void scheduleInstallRetry(ChannelPipeline pipeline, long deadline) {
        pipeline.channel().eventLoop().schedule(() -> {
            if (pipeline.channel().isActive() && pipeline.get(ZstdNettyPipeline.INBOUND_HANDLER) == null) {
                install(pipeline, true);
            }
        }, 100L, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static boolean enqueuePending(String host, int port, ProtocolProbe.ProbeResult protocol) {
        var pending = new PendingConnection(
            host.toLowerCase(Locale.ROOT), port, protocol.serverLevel(),
            System.currentTimeMillis() + PENDING_CONNECT_TTL_MS);
        var queue = PENDING.computeIfAbsent(key(host, port), ignored -> new ConcurrentLinkedQueue<>());
        while (queue.size() >= MAX_PENDING_PER_KEY) queue.poll();
        queue.add(pending);
        ZstdNetClient.logger().info("prepared ZstdNet pipeline for " + host + ":" + port);
        lastState = ZstdState.ACTIVE;
        return true;
    }

    public static void reposition(ChannelPipeline pipeline) {
        ZstdNetClient.logger().debug("reposition requested: channel="
            + (pipeline == null ? "null" : pipeline.channel().getClass().getName())
            + ", names=" + (pipeline == null ? "[]" : pipeline.names()));
        if (pipeline == null || isDatagramPipeline(pipeline)
            || pipeline.get(ZstdNettyPipeline.INBOUND_HANDLER) == null) {
            ZstdNetClient.logger().debug("reposition skipped: pipeline unavailable or not ZstdNet TCP");
            return;
        }

        try {
            ZstdNettyPipeline.reposition(pipeline);
        } catch (RuntimeException e) {
            var logger = ZstdNetClient.logger();
            logger.warn("failed to reposition ZstdNet pipeline: " + e);
        }
    }

    private static boolean isDatagramPipeline(ChannelPipeline pipeline) {
        return pipeline == null || pipeline.channel() instanceof DatagramChannel;
    }

    private static String key(String host, int port) {
        return ProtocolProbe.normalizeHost(host) + ":" + port;
    }

    private static PendingConnection pollPendingByPort(int port) {
        for (var entry : PENDING.entrySet()) {
            var queue = entry.getValue();
            PendingConnection pending;
            int scan = queue.size();
            while (scan-- > 0 && (pending = queue.poll()) != null) {
                if (pending.port() == port && !pending.expired()) return pending;
                if (!pending.expired()) queue.offer(pending);
            }
        }
        return null;
    }

    private static void expireStale() {
        PENDING.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(PendingConnection::expired);
            return entry.getValue().isEmpty();
        });
    }

    private record PendingConnection(String host, int port, int compressionLevel, long expiresAtMs) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }
}
