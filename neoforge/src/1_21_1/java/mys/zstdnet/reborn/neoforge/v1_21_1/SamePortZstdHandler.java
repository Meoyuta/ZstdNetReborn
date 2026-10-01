package mys.zstdnet.reborn.neoforge.v1_21_1;

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
import java.util.function.IntSupplier;

final class SamePortZstdHandler extends ByteToMessageDecoder {
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
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
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
        if (startsWithMagic(in)) {
            in.skipBytes(ZstdFrameCodec.MAGIC.length);
            mode = Mode.ZSTD;
            initializeZstdPipeline(ctx, in, out);
            return;
        }

        Boolean rawLogin = isRawLogin(in);
        if (rawLogin == null) {
            return;
        }
        if (rawLogin) {
            logger.warn("rejected raw login from " + ctx.channel().remoteAddress());
            in.skipBytes(in.readableBytes());
            ctx.writeAndFlush(Unpooled.wrappedBuffer(loginDisconnectPacket(config.rawLoginMessage())))
                .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        mode = Mode.RAW;
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
            countConnection(ctx);
            var offered = dictionaryStore.dictionary();
            var dictionaryActive = new AtomicBoolean();
            ctx.channel().closeFuture().addListener(future -> {
                if (dictionaryActive.compareAndSet(true, false)) stats.addDictionaryConnection(offered.id(), -1);
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
                            stats.addDictionaryConnection(id, 1);
                            logger.info("Dictionary ACK confirmed from " + ctx.channel().remoteAddress()
                                + ", id=" + Long.toUnsignedString(id));
                        }
                    }
                    public void failed(String message) { logger.warn(message); }
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
        });
    }

    private ZstdFrameStats serverStats() {
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
            public void inboundSample(byte[] raw) {
                dictionaryTrainer.capture(true, raw);
                benchmark.capture(raw);
            }

            @Override
            public void outboundSample(byte[] raw) {
                dictionaryTrainer.capture(false, raw);
                benchmark.capture(raw);
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
