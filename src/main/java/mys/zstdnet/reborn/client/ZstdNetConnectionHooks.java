package mys.zstdnet.reborn.client;

import mys.zstdnet.reborn.core.netty.ZstdFrameStats;
import mys.zstdnet.reborn.core.netty.ZstdDictionarySession;
import mys.zstdnet.reborn.core.netty.ZstdNettyPipeline;
import mys.zstdnet.reborn.neoforge.helper.SableCompat;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramChannel;

import java.util.Locale;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;

public final class ZstdNetConnectionHooks {
    private static final long PENDING_CONNECT_TTL_MS = 15_000L;
    private static final Map<String, ConcurrentLinkedQueue<PendingConnection>> PENDING = new ConcurrentHashMap<>();
    private static final int MAX_PENDING_PER_KEY = 8;

    private ZstdNetConnectionHooks() {
    }

    public static boolean prepare(String host, int port) {
        expireStale();
        if (host == null || host.isBlank()) {
            ZstdNetClient.logger().debug("prepare skipped: blank host");
            return false;
        }

        var config = ZstdNetClient.config();
        var capability = CapabilityProbe.cached(host, port);
        if (!Boolean.TRUE.equals(capability)) {
            PENDING.remove(key(host, port));
            CapabilityProbe.start(host, port);
            ZstdNetClient.logger().debug("ZstdNet capability probe pending or failed; using ordinary connection for "
                + host + ":" + port);
            return false;
        }

        var pending = new PendingConnection(
            host.toLowerCase(Locale.ROOT),
            port,
            config.compressionLevel(),
            System.currentTimeMillis() + PENDING_CONNECT_TTL_MS
        );
        var queue = PENDING.computeIfAbsent(key(host, port), ignored -> new ConcurrentLinkedQueue<>());
        while (queue.size() >= MAX_PENDING_PER_KEY) queue.poll();
        queue.add(pending);
        ZstdNetClient.logger().info("prepared ZstdNet pipeline for " + host + ":" + port);
        return true;
    }

    public static void install(ChannelPipeline pipeline) {
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
        var remote = pipeline.channel().remoteAddress();
        if (!(remote instanceof InetSocketAddress address)) {
            ZstdNetClient.logger().debug("install skipped: TCP remote address unavailable");
            return;
        }
        var pending = pollPending(address.getHostString(), address.getPort());
        if (pending == null) {
            ZstdNetClient.logger().debug("install skipped: no live pending TCP connection");
            return;
        }

        ZstdNettyPipeline.install(
            pipeline,
            pending.compressionLevel(),
            true,
            ZstdFrameStats.NONE,
            ZstdDictionarySession.client(ZstdNetClient::receiveServerDictionary,
                ZstdNetClient.dictionaryDownloadListener(), ZstdNetClient.uplinkDictionary())
        );
        ZstdNetClient.logger().info("installed ZstdNet pipeline for " + pending.host() + ":" + pending.port());
        ZstdNetClient.logger().debug("TCP pipeline installed: " + pipeline.names());
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
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static PendingConnection pollPending(String host, int port) {
        var queue = PENDING.get(key(host, port));
        if (queue == null) return null;
        PendingConnection pending;
        while ((pending = queue.poll()) != null) {
            if (!pending.expired()) return pending;
        }
        PENDING.remove(key(host, port), queue);
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
