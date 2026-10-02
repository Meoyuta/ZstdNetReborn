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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

public final class SamePortZstdInjector implements AutoCloseable {
    public enum InjectState { OK, NO_CHANNELS, EXCEPTION }
    private static volatile InjectState lastState = InjectState.OK;
    private static volatile Throwable lastError;
    private static final String ACCEPT_HANDLER = "zstdnet-accept-injector";
    private static final String CONNECTION_HANDLER = "zstdnet-same-port-codec";
    // Resolve once and read directly from a static final exact handle, following:
    // https://gist.github.com/burningtnt/e4b39edadd0637cfb78e98dd7cfe3b87
    private static final VarHandle CHANNELS = resolveChannelsHandle();

    private final MinecraftServer minecraftServer;
    private final ZstdNetConfig config;
    private final ZstdNetLogger logger;
    private final ZstdDictionaryStore dictionaryStore;
    private final ZstdDictionaryTrainer dictionaryTrainer;
    private final CompressionBenchmark benchmark;
    private final IntSupplier compressionLevel;
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
        this.minecraftServer = Objects.requireNonNull(minecraftServer, "minecraftServer");
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dictionaryStore = Objects.requireNonNull(dictionaryStore, "dictionaryStore");
        this.dictionaryTrainer = Objects.requireNonNull(dictionaryTrainer, "dictionaryTrainer");
        this.benchmark = Objects.requireNonNull(benchmark, "benchmark");
        this.compressionLevel = Objects.requireNonNull(compressionLevel, "compressionLevel");
    }

    public MinecraftServer server() {
        return minecraftServer;
    }

    public void inject() {
        List<Channel> serverChannels = serverChannels();
        if (serverChannels.isEmpty()) {
            lastState = InjectState.NO_CHANNELS;
            throw new IllegalStateException("could not find Minecraft server Netty channels");
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
            throw e;
        }
        lastState = InjectState.OK;
        lastError = null;
        logger.info("ZstdNet same-port injection active on " + serverChannels.size() + " server channel(s)");
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

    @SuppressWarnings("unchecked")
    private static List<ChannelFuture> channelFutures(ServerConnectionListener connectionListener) {
        List<ChannelFuture> futures = (List<ChannelFuture>) CHANNELS.get(connectionListener);
        synchronized (futures) {
            return List.copyOf(futures);
        }
    }

    private static VarHandle resolveChannelsHandle() {
        try {
            return MethodHandles.privateLookupIn(ServerConnectionListener.class, MethodHandles.lookup())
                .findVarHandle(ServerConnectionListener.class, "channels", List.class)
                .withInvokeExactBehavior();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final class AcceptInjector extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Channel child && child.pipeline().get(CONNECTION_HANDLER) == null) {
                child.pipeline().addFirst(
                    CONNECTION_HANDLER,
                    new SamePortZstdHandler(config, stats, logger, dictionaryStore, dictionaryTrainer,
                        benchmark, compressionLevel)
                );
            }
            super.channelRead(ctx, msg);
        }
    }
}
