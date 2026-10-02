package mys.zstdnet.reborn.neoforge.network;

import mys.zstdnet.reborn.core.ZstdNetConfig;
import mys.zstdnet.reborn.core.benchmark.CompressionBenchmark;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import mys.zstdnet.reborn.core.netty.MinecraftCompressionDisabler;
import mys.zstdnet.reborn.core.netty.ZstdDictionarySession;
import mys.zstdnet.reborn.core.netty.ZstdFrameStats;
import mys.zstdnet.reborn.core.netty.ZstdNettyPipeline;
import mys.zstdnet.reborn.core.netty.ZstdStreamHeader;
import mys.zstdnet.reborn.core.protocol.ByteArrayOps;
import mys.zstdnet.reborn.core.protocol.HandshakePacket;
import mys.zstdnet.reborn.core.protocol.VarIntCodec;
import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import mys.zstdnet.reborn.core.stats.TrafficStats;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

final class SamePortZstdHandler extends ByteToMessageDecoder {
    private static final int MAX_CONNECTIONS_PER_IP = 3;
    private static final int MAX_HANDSHAKES_PER_MINUTE = 10;
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 10_000L;
    private static final ConcurrentHashMap<String, AtomicInteger> ACTIVE_BY_IP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Window> HANDSHAKES_BY_IP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> DOWNLINK_DICTIONARY_SEEN = new ConcurrentHashMap<>();
    private static final AtomicInteger ADMISSION_ATTEMPTS = new AtomicInteger();
    private enum Mode {
        UNDECIDED,
        RAW,
        ZSTD
    }

    private final ZstdNetConfig config;
    private final TrafficStats stats;
    private final ZstdNetLogger logger;
    private final ZstdDictionaryStore dictionaryStore;
    private final ZstdDictionaryTrainer dictionaryTrainer;
    private final CompressionBenchmark benchmark;
    private final IntSupplier compressionLevel;
    private Mode mode = Mode.UNDECIDED;
    private boolean streamHeaderRead;
    private boolean admitted;
    private String remoteIp;

    SamePortZstdHandler(
        ZstdNetConfig config,
        TrafficStats stats,
        ZstdNetLogger logger,
        ZstdDictionaryStore dictionaryStore,
        ZstdDictionaryTrainer dictionaryTrainer,
        CompressionBenchmark benchmark,
        IntSupplier compressionLevel
    ) {
        this.config = config;
        this.stats = stats;
        this.logger = logger;
        this.dictionaryStore = dictionaryStore;
        this.dictionaryTrainer = dictionaryTrainer;
        this.benchmark = benchmark;
        this.compressionLevel = compressionLevel;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        ctx.executor().schedule(() -> {
            if (mode == Mode.UNDECIDED && ctx.channel().isActive()) {
                logger.warn("closing incomplete ZstdNet handshake from " + ctx.channel().remoteAddress());
                ctx.close();
            }
        }, HANDSHAKE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        logger.debug("protocol decode remote=" + ctx.channel().remoteAddress() + " mode=" + mode
            + " readable=" + in.readableBytes());
        if (mode == Mode.ZSTD) {
            initializeZstdPipeline(ctx, in, out);
            return;
        }
        if (mode == Mode.RAW) {
            out.add(in.readRetainedSlice(in.readableBytes()));
            return;
        }

        if (in.readableBytes() < ZstdFrameCodec.MAGIC.length) {
            return;
        }
        if (startsWith(in, ZstdFrameCodec.CAPABILITY_MAGIC)) {
            // Capability probes never consume a connection slot or handshake budget.
            in.skipBytes(ZstdFrameCodec.CAPABILITY_MAGIC.length);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(ZstdFrameCodec.CAPABILITY_RESPONSE))
                .addListener(ChannelFutureListener.CLOSE);
            logger.debug("answered ZstdNet capability probe from " + ctx.channel().remoteAddress());
            return;
        }
        if (startsWithMagic(in)) {
            if (!admit(ctx)) return;
            in.skipBytes(ZstdFrameCodec.MAGIC.length);
            mode = Mode.ZSTD;
            logger.debug("protocol mode ZSTD remote=" + ctx.channel().remoteAddress());
            initializeZstdPipeline(ctx, in, out);
            return;
        }

        Boolean rawLogin = isRawLogin(in);
        if (rawLogin == null) {
            return;
        }
        if (rawLogin) {
            logger.debug("protocol mode rejected RAW login remote=" + ctx.channel().remoteAddress());
            logger.warn("rejected raw login from " + ctx.channel().remoteAddress());
            in.skipBytes(in.readableBytes());
            ctx.writeAndFlush(Unpooled.wrappedBuffer(loginDisconnectPacket(config.rawLoginMessage())))
                .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        mode = Mode.RAW;
        logger.debug("protocol mode RAW passthrough remote=" + ctx.channel().remoteAddress());
        if (in.isReadable()) {
            out.add(in.readRetainedSlice(in.readableBytes()));
        }
        ctx.pipeline().remove(this);
    }

    private void initializeZstdPipeline(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!streamHeaderRead) {
            if (!ZstdStreamHeader.read(in)) {
                return;
            }
            streamHeaderRead = true;
            logger.debug("ZSTD stream header accepted remote=" + ctx.channel().remoteAddress());
            countConnection(ctx);
            var selectedDictionary = dictionaryStore.dictionary();
            var remoteAddress = ctx.channel().remoteAddress();
            var dictionaryKey = remoteAddress instanceof java.net.InetSocketAddress socket
                ? socket.getHostString() : String.valueOf(remoteAddress);
            var offered = selectedDictionary;
            if (selectedDictionary != null) {
                var shouldOffer = new AtomicBoolean();
                DOWNLINK_DICTIONARY_SEEN.compute(dictionaryKey, (key, previousId) -> {
                    if (previousId == null || previousId.longValue() != selectedDictionary.id()) {
                        shouldOffer.set(true);
                        return selectedDictionary.id();
                    }
                    return previousId;
                });
                if (!shouldOffer.get()) offered = null;
            }
            if (selectedDictionary != null && offered == null) {
                logger.debug("skipping repeated downlink dictionary offer for " + dictionaryKey);
            }
            var dictionaryActive = new AtomicBoolean();
            var activeDictionaryId = new java.util.concurrent.atomic.AtomicLong();
            var fallbackActive = new AtomicBoolean();
            ctx.channel().closeFuture().addListener(future -> {
                if (dictionaryActive.compareAndSet(true, false)) stats.addDictionaryConnection(activeDictionaryId.get(), -1);
                if (fallbackActive.compareAndSet(true, false)) stats.addActiveDictionaryFallback(-1);
            });
            var session = ZstdDictionarySession.server(offered, dictionaryStore.uplinkDictionary(),
                new mys.zstdnet.reborn.core.netty.ZstdDictionaryDownloadListener() {
                    public void started(long id, int bytes) {
                        logger.info("Sending dictionary id=" + Long.toUnsignedString(id)
                            + " bytes=" + bytes + " to " + ctx.channel().remoteAddress());
                    }
                    public void progress(int received, int total) {}
                    public void completed(long id) {
                        if (ctx.channel().isActive() && dictionaryActive.compareAndSet(false, true)) {
                            activeDictionaryId.set(id);
                            stats.addDictionaryConnection(id, 1);
                            logger.info("Dictionary ACK confirmed from " + ctx.channel().remoteAddress()
                                + ", id=" + Long.toUnsignedString(id));
                        }
                    }
                    public void failed(String message) {
                        logger.warn(message);
                    }
                    public void failed(mys.zstdnet.reborn.core.netty.ZstdDictionaryDownloadListener.DictionaryFailure reason,
                                       String message) {
                        if (reason == mys.zstdnet.reborn.core.netty.ZstdDictionaryDownloadListener.DictionaryFailure.INBOUND_ID_MISMATCH
                            || reason == mys.zstdnet.reborn.core.netty.ZstdDictionaryDownloadListener.DictionaryFailure.OFFER_REJECTED) {
                            stats.addDictionaryFallback();
                            if (fallbackActive.compareAndSet(false, true)) stats.addActiveDictionaryFallback(1);
                        }
                        logger.warn(message);
                    }
                });
            ZstdNettyPipeline.install(
                ctx.pipeline(),
                compressionLevel,
                false,
                serverStats(),
                session
            );
            // Send immediately, without waiting for an unrelated Minecraft response.
            if (session.hasPendingControl()) {
                var encoder = ctx.pipeline().context(ZstdNettyPipeline.OUTBOUND_HANDLER);
                ((mys.zstdnet.reborn.core.netty.ZstdNettyEncoder) encoder.handler())
                    .write(encoder, Unpooled.buffer(0), ctx.newPromise());
                encoder.flush();
            } else {
                logger.info("Connection uses ordinary ZSTD (no dictionary): " + ctx.channel().remoteAddress());
            }
            MinecraftCompressionDisabler.install(ctx.pipeline());
            logger.info("accepted ZstdNet client connection from " + ctx.channel().remoteAddress());
            logger.debug("ZSTD pipeline after install remote=" + ctx.channel().remoteAddress()
                + " names=" + ctx.pipeline().names());
        }
        if (in.isReadable()) {
            out.add(in.readRetainedSlice(in.readableBytes()));
        }
        ctx.pipeline().remove(this);
    }

    private void countConnection(ChannelHandlerContext ctx) {
        var active = new AtomicBoolean(true);
        stats.addConnection(1);
        ctx.channel().closeFuture().addListener(future -> {
            if (active.compareAndSet(true, false)) {
                stats.addConnection(-1);
            }
            if (admitted && remoteIp != null) {
                ACTIVE_BY_IP.computeIfPresent(remoteIp, (key, count) -> count.decrementAndGet() <= 0 ? null : count);
                admitted = false;
            }
        });
    }

    private boolean admit(ChannelHandlerContext ctx) {
        if ((ADMISSION_ATTEMPTS.incrementAndGet() & 1023) == 0) {
            var now = System.currentTimeMillis();
            HANDSHAKES_BY_IP.entrySet().removeIf(entry -> now - entry.getValue().startedAt > 60_000L);
            ACTIVE_BY_IP.entrySet().removeIf(entry -> entry.getValue().get() <= 0);
        }
        var address = ctx.channel().remoteAddress();
        var ip = address instanceof java.net.InetSocketAddress socket
            ? socket.getAddress().getHostAddress() : String.valueOf(address);
        var now = System.currentTimeMillis();
        var window = HANDSHAKES_BY_IP.computeIfAbsent(ip, ignored -> new Window(now));
        synchronized (window) {
            if (now - window.startedAt > 60_000L) {
                window.startedAt = now;
                window.count = 0;
            }
            if (++window.count > MAX_HANDSHAKES_PER_MINUTE) {
                logger.warn("rate-limited ZstdNet handshake from " + ip);
                ctx.close();
                return false;
            }
        }
        var active = ACTIVE_BY_IP.computeIfAbsent(ip, ignored -> new AtomicInteger());
        if (active.incrementAndGet() > MAX_CONNECTIONS_PER_IP) {
            active.decrementAndGet();
            logger.warn("connection limit reached for ZstdNet client " + ip);
            ctx.close();
            return false;
        }
        remoteIp = ip;
        admitted = true;
        ctx.channel().closeFuture().addListener(future -> {
            if (admitted && remoteIp != null) {
                ACTIVE_BY_IP.computeIfPresent(remoteIp, (key, count) -> count.decrementAndGet() <= 0 ? null : count);
                admitted = false;
            }
        });
        return true;
    }

    private static final class Window {
        long startedAt;
        int count;
        Window(long startedAt) { this.startedAt = startedAt; }
    }

    ZstdFrameStats serverStats() {
        return new ZstdFrameStats() {
            @Override
            public void inbound(long rawBytes, long wireBytes) {
                // Server perspective: inbound is data received from the client (download).
                stats.addRawDown(rawBytes);
                stats.addWireDown(wireBytes);
            }

            @Override
            public void outbound(long rawBytes, long wireBytes) {
                // Server perspective: outbound is data sent to the client (upload).
                stats.addRawUp(rawBytes);
                stats.addWireUp(wireBytes);
            }

            @Override
            public void inboundSample(ByteBuf raw) {
            if (dictionaryTrainer.isActive()) dictionaryTrainer.capture(true, raw);
                if (benchmark.isActive()) benchmark.capture(raw);
            }

            @Override
            public void outboundSample(ByteBuf raw) {
                if (dictionaryTrainer.isActive()) dictionaryTrainer.capture(false, raw);
                if (benchmark.isActive()) benchmark.capture(raw);
            }
        };
    }

    private boolean startsWithMagic(ByteBuf in) {
        for (int i = 0; i < ZstdFrameCodec.MAGIC.length; i++) {
            if (in.getByte(in.readerIndex() + i) != ZstdFrameCodec.MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(ByteBuf in, byte[] magic) {
        if (in.readableBytes() < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (in.getByte(in.readerIndex() + i) != magic[i]) return false;
        }
        return true;
    }

    private Boolean isRawLogin(ByteBuf in) throws IOException {
        var start = in.readerIndex();
        var length = readVarInt(in);
        if (length == null) {
            in.readerIndex(start);
            return null;
        }
        if (length < 0 || length > 4096) {
            in.readerIndex(start);
            return false;
        }
        if (in.readableBytes() < length) {
            in.readerIndex(start);
            return null;
        }

        var payload = new byte[length];
        in.readBytes(payload);
        in.readerIndex(start);
        var handshake = HandshakePacket.parse(payload);
        return handshake != null && handshake.nextState() == HandshakePacket.LOGIN;
    }

    private static Integer readVarInt(ByteBuf in) throws IOException {
        var value = 0;
        var shift = 0;
        for (var i = 0; i < 5; i++) {
            if (!in.isReadable()) {
                return null;
            }
            var b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("varint too large");
    }

    private static byte[] loginDisconnectPacket(String message) {
        var escaped = (message == null ? "ZstdNet required" : message)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        var json = ("{\"text\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
        var payload = ByteArrayOps.concat(
            VarIntCodec.encode(0),
            VarIntCodec.encode(json.length),
            json
        );
        return ByteArrayOps.concat(VarIntCodec.encode(payload.length), payload);
    }
}
