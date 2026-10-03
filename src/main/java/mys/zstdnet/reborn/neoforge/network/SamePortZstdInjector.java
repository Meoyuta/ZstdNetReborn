package mys.zstdnet.reborn.neoforge.network;

import mys.zstdnet.reborn.core.ZstdNetConfig;
import mys.zstdnet.reborn.core.benchmark.CompressionBenchmark;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import mys.zstdnet.reborn.core.stats.TrafficStats;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramChannel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import mys.zstdnet.reborn.neoforge.mixin.ServerConnectionListenerAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

public final class SamePortZstdInjector implements AutoCloseable {
    public enum InjectState { OK, NO_CHANNELS, ACCESSOR_MISSING, EXCEPTION }
    private static volatile InjectState lastState = InjectState.OK;
    private static volatile Throwable lastError;
    private static final String ACCEPT_HANDLER = "zstdnet-accept-injector";
    private static final String CONNECTION_HANDLER = "zstdnet-same-port-codec";
    private final MinecraftServer minecraftServer;
    private final ZstdNetConfig config;
    private final ZstdNetLogger logger;
    private final ZstdDictionaryStore dictionaryStore;
    private final ZstdDictionaryTrainer dictionaryTrainer;
    private final CompressionBenchmark benchmark;
    private final IntSupplier compressionLevel;
    private final IntSupplier clientCompressionLevel;
    private final TrafficStats stats = new TrafficStats();
    private final List<Channel> injectedServerChannels = new ArrayList<>();

    public SamePortZstdInjector(
        MinecraftServer minecraftServer,
        ZstdNetConfig config,
        ZstdNetLogger logger,
        ZstdDictionaryStore dictionaryStore,
        ZstdDictionaryTrainer dictionaryTrainer,
        CompressionBenchmark benchmark,
        IntSupplier compressionLevel
    ) {
        this(minecraftServer, config, logger, dictionaryStore, dictionaryTrainer, benchmark,
            compressionLevel, () -> 6);
    }

    public SamePortZstdInjector(
        MinecraftServer minecraftServer,
        ZstdNetConfig config,
        ZstdNetLogger logger,
        ZstdDictionaryStore dictionaryStore,
        ZstdDictionaryTrainer dictionaryTrainer,
        CompressionBenchmark benchmark,
        IntSupplier compressionLevel,
        IntSupplier clientCompressionLevel
    ) {
        this.minecraftServer = Objects.requireNonNull(minecraftServer, "minecraftServer");
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dictionaryStore = Objects.requireNonNull(dictionaryStore, "dictionaryStore");
        this.dictionaryTrainer = Objects.requireNonNull(dictionaryTrainer, "dictionaryTrainer");
        this.benchmark = Objects.requireNonNull(benchmark, "benchmark");
        this.compressionLevel = Objects.requireNonNull(compressionLevel, "compressionLevel");
        this.clientCompressionLevel = Objects.requireNonNull(clientCompressionLevel, "clientCompressionLevel");
    }

    public MinecraftServer server() {
        return minecraftServer;
    }

    public void inject() {
        List<Channel> serverChannels = serverChannels();
        if (serverChannels.isEmpty()) {
            switch (lastState) {
                case ACCESSOR_MISSING -> logger.error(
                    "ZstdNet transport upgrade: DISABLED (reason=accessor_missing; keeping vanilla networking)");
                case EXCEPTION -> logger.error(
                    "ZstdNet transport upgrade: DISABLED (reason=accessor_exception; keeping vanilla networking)");
                default -> {
                    lastState = InjectState.NO_CHANNELS;
                    logger.warn("ZstdNet transport upgrade: DISABLED (reason=no_server_channels; keeping vanilla networking)");
                }
            }
            return;
        }

        try {
            for (Channel channel : serverChannels) {
                channel.eventLoop().submit(() -> {
                    if (channel.pipeline().get(ACCEPT_HANDLER) == null) {
                        channel.pipeline().addFirst(ACCEPT_HANDLER, new AcceptInjector());
                    }
                }).syncUninterruptibly();
                injectedServerChannels.add(channel);
            }
        } catch (RuntimeException e) {
            lastState = InjectState.EXCEPTION;
            lastError = e;
            close();
            logger.error("ZstdNet transport upgrade: DISABLED (reason=inject_exception; keeping vanilla networking): " + e);
            throw e;
        }
        lastState = InjectState.OK;
        lastError = null;
        logger.info("ZstdNet transport upgrade: ACTIVE (server_channels=" + serverChannels.size() + ")");
    }

    public TrafficStats.Snapshot snapshot() {
        return stats.snapshot();
    }

    public int dictionaryConnections() { return stats.dictionaryConnections(); }
    public int dictionaryConnections(long id) { return stats.dictionaryConnections(id); }
    public int dictionaryFallbacks() { return stats.dictionaryFallbacks(); }
    public int activeDictionaryFallbacks() { return stats.activeDictionaryFallbacks(); }

    public static InjectState lastState() { return lastState; }
    public static Throwable lastError() { return lastError; }

    public static ZstdState zstdState() {
        return switch (lastState) {
            case OK -> ZstdState.ACTIVE;
            case NO_CHANNELS, EXCEPTION -> ZstdState.INJECT_FAILED;
            case ACCESSOR_MISSING -> ZstdState.ACCESSOR_MISSING;
        };
    }

    public static ZstdState failureStateOrDisabled() {
        return switch (lastState) {
            case OK -> ZstdState.SERVER_DISABLED;
            case ACCESSOR_MISSING -> ZstdState.ACCESSOR_MISSING;
            case NO_CHANNELS, EXCEPTION -> ZstdState.INJECT_FAILED;
        };
    }

    @Override
    public void close() {
        for (Channel channel : injectedServerChannels) {
            if (!channel.isOpen()) {
                continue;
            }
            channel.eventLoop().submit(() -> {
                if (channel.pipeline().get(ACCEPT_HANDLER) != null) {
                    channel.pipeline().remove(ACCEPT_HANDLER);
                }
            }).syncUninterruptibly();
        }
        injectedServerChannels.clear();
        logger.info("ZstdNet same-port injection stopped");
    }

    private List<Channel> serverChannels() {
        var connectionListener = minecraftServer.getConnection();
        var channels = new ArrayList<Channel>();
        for (var future : channelFutures(connectionListener)) {
            if (future != null && future.channel() != null
                && !(future.channel() instanceof DatagramChannel)) {
                channels.add(future.channel());
            }
        }
        return channels;
    }

    private List<ChannelFuture> channelFutures(ServerConnectionListener connectionListener) {
        try {
            if (!(connectionListener instanceof ServerConnectionListenerAccessor accessor)) {
                lastState = InjectState.ACCESSOR_MISSING;
                lastError = new IllegalStateException("ServerConnectionListener accessor mixin was not applied");
                logger.error("ZstdNet transport upgrade: DISABLED (reason=accessor_missing; keeping vanilla networking)");
                return List.of();
            }
            List<ChannelFuture> futures = accessor.zstdnet$getChannels();
            if (futures == null) return List.of();
            synchronized (futures) {
                return List.copyOf(futures);
            }
        } catch (RuntimeException error) {
            lastState = InjectState.EXCEPTION;
            lastError = error;
            logger.error("ZstdNet transport upgrade: DISABLED (reason=accessor_exception; keeping vanilla networking): " + error);
            return List.of();
        }
    }

    private final class AcceptInjector extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Channel child && child.pipeline().get(CONNECTION_HANDLER) == null) {
                child.pipeline().addFirst(
                    CONNECTION_HANDLER,
                    new SamePortZstdHandler(config, stats, logger, dictionaryStore, dictionaryTrainer,
                        benchmark, compressionLevel, clientCompressionLevel)
                );
            }
            super.channelRead(ctx, msg);
        }
    }
}
