package mys.zstdnet.reborn.client;

import mys.zstdnet.reborn.core.netty.ZstdFrameStats;
import mys.zstdnet.reborn.core.netty.ZstdDictionarySession;
import mys.zstdnet.reborn.core.netty.ZstdNettyPipeline;
import io.netty.channel.ChannelPipeline;

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
            return false;
        }

        var config = ZstdNetClient.config();
        if (!config.enabledFor(host, port)) {
            PENDING.set(null);
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
        var pending = PENDING.getAndSet(null);
        if (pending == null || pending.expired()) {
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
    }

    public static void reposition(ChannelPipeline pipeline) {
        if (pipeline == null || pipeline.get(ZstdNettyPipeline.INBOUND_HANDLER) == null) {
            return;
        }

        try {
            ZstdNettyPipeline.reposition(pipeline);
        } catch (RuntimeException e) {
            var logger = ZstdNetClient.logger();
            logger.warn("failed to reposition ZstdNet pipeline: " + e);
        }
    }

    private record PendingConnection(String host, int port, int compressionLevel, long expiresAtMs) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }
}
