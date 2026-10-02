package mys.zstdnet.reborn.client;

import mys.zstdnet.reborn.core.netty.ZstdFrameStats;
import mys.zstdnet.reborn.core.netty.ZstdDictionarySession;
import mys.zstdnet.reborn.core.netty.ZstdNettyPipeline;
import mys.zstdnet.reborn.neoforge.helper.SableCompat;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramChannel;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class ZstdNetConnectionHooks {
    private static final long PENDING_CONNECT_TTL_MS = 15_000L;
    private static final AtomicReference<PendingConnection> PENDING = new AtomicReference<>();

    private ZstdNetConnectionHooks() {
    }

    public static boolean prepare(String host, int port) {
        if (host == null || host.isBlank()) {
            PENDING.set(null);
            ZstdNetClient.logger().debug("prepare skipped: blank host");
            return false;
        }

        var config = ZstdNetClient.config();
        if (!config.enabledFor(host, port)) {
            PENDING.set(null);
            ZstdNetClient.logger().debug("prepare skipped: disabled for " + host + ":" + port);
            return false;
        }

        var pending = new PendingConnection(
            host.toLowerCase(Locale.ROOT),
            port,
            config.compressionLevel(),
            System.currentTimeMillis() + PENDING_CONNECT_TTL_MS
        );
        PENDING.set(pending);
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
                + pipeline.names());
            return;
        }
        var pending = PENDING.getAndSet(null);
        if (pending == null || pending.expired()) {
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

    private record PendingConnection(String host, int port, int compressionLevel, long expiresAtMs) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }
}
