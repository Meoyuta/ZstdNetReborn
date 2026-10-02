package mys.zstdnet.reborn.core.netty;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import mys.zstdnet.reborn.core.protocol.ZstdPersistentStreamCodec;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

public final class ZstdNettyDecoder extends ByteToMessageDecoder {
    private final ZstdFrameStats stats;
    private final ZstdDictionarySession dictionarySession;
    private boolean streamHeaderRead;
    private ZstdPersistentStreamCodec persistentStream;
    private long persistentDictionaryId = Long.MIN_VALUE;
    private boolean ownsPersistentStream = true;
    private boolean asyncDecompressionInFlight;
    private static final int ASYNC_DECOMPRESSION_THRESHOLD = 64 * 1024;
    private static final int MAX_PENDING_INBOUND_BYTES = 8 * 1024 * 1024;

    public ZstdNettyDecoder(ZstdFrameStats stats) {
        this(stats, null);
    }

    public ZstdNettyDecoder(ZstdFrameStats stats, ZstdDictionarySession dictionarySession) {
        this.stats = stats == null ? ZstdFrameStats.NONE : stats;
        this.dictionarySession = dictionarySession;
    }

    ZstdNettyDecoder copyForMove() {
        var copy = new ZstdNettyDecoder(stats, dictionarySession);
        copy.streamHeaderRead = streamHeaderRead;
        copy.persistentStream = persistentStream;
        copy.persistentDictionaryId = persistentDictionaryId;
        copy.asyncDecompressionInFlight = false;
        ownsPersistentStream = false;
        return copy;
    }

    @Override
    protected void decode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (asyncDecompressionInFlight) {
            if (in.readableBytes() >= MAX_PENDING_INBOUND_BYTES) {
                ctx.channel().config().setAutoRead(false);
            }
            if (in.readableBytes() > MAX_PENDING_INBOUND_BYTES) {
                throw new IOException("ZstdNet inbound cumulation exceeded limit while decompressing");
            }
            return;
        }
        if (dictionarySession != null && dictionarySession.expectsHeader() && !streamHeaderRead) {
            if (!ZstdStreamHeader.read(in)) {
                return;
            }
            streamHeaderRead = true;
        }
        while (in.isReadable()) {
            var frameStart = in.readerIndex();
            var rawLength = readVarInt(in);
            if (rawLength == null) {
                in.readerIndex(frameStart);
                return;
            }
            var storedTag = readVarInt(in);
            if (storedTag == null) {
                in.readerIndex(frameStart);
                return;
            }
            if (rawLength < 0 || rawLength > ZstdFrameCodec.MAX_FRAME_BYTES
                || storedTag < 0 || storedTag > (ZstdFrameCodec.MAX_FRAME_BYTES << 1) + 1) {
                throw new IOException("invalid zstd frame length");
            }
            var payloadLength = storedTag == 0 ? rawLength : storedTag >>> 1;
            if (rawLength == 0) {
                if (storedTag == 0 || (storedTag & 1) != 0 || payloadLength == 0
                    || payloadLength > mys.zstdnet.reborn.core.dictionary.ZstdDictionary.MAX_DOWNLINK_BYTES + 14) {
                    throw new IOException("invalid ZstdNet control frame");
                }
                var payloadStart = in.readerIndex();
                if (in.readableBytes() < payloadLength) {
                    if (dictionarySession != null) {
                        dictionarySession.observeControlProgress(in, payloadStart, payloadLength);
                    }
                    in.readerIndex(frameStart);
                    return;
                }
                var control = in.readSlice(payloadLength);
                if (dictionarySession == null) {
                    throw new IOException("received ZstdNet control frame without a session");
                }
                dictionarySession.receiveControl(control);
                if (dictionarySession.consumeInboundStreamReset()) {
                    closePersistentStream();
                    persistentStream = null;
                    persistentDictionaryId = Long.MIN_VALUE;
                }
                if (dictionarySession.hasPendingControl()) {
                    // Start at the encoder context: bypass Minecraft's packet encoder and length prepender.
                    var encoder = ctx.pipeline().context(ZstdNettyPipeline.OUTBOUND_HANDLER);
                    if (encoder == null) throw new IOException("Missing ZstdNet encoder for dictionary acknowledgement");
                    ((ZstdNettyEncoder) encoder.handler()).write(encoder, ctx.alloc().buffer(0), ctx.newPromise());
                    encoder.flush();
                }
                continue;
            }
            if (payloadLength == 0) {
                throw new IOException("invalid zstd frame payload length");
            }
            if (in.readableBytes() < payloadLength) {
                in.readerIndex(frameStart);
                return;
            }

            if (storedTag == 0) {
                var raw = ctx.alloc().buffer(Math.min(rawLength, 64 * 1024), rawLength);
                try {
                    raw.writeBytes(in, rawLength);
                } catch (Throwable error) {
                    raw.release();
                    throw error;
                }
                stats.inbound(rawLength, in.readerIndex() - frameStart);
                stats.inboundSample(raw);
                out.add(raw);
                continue;
            }

            var payload = in.readRetainedSlice(payloadLength);
            var usesDictionary = storedTag != 0 && (storedTag & 1) == 1;
            mys.zstdnet.reborn.core.dictionary.ZstdDictionary dictionary = dictionarySession == null
                ? null
                : dictionarySession.activeInboundDictionary();
            if (usesDictionary && dictionary == null) {
                payload.release();
                throw new IOException("received dictionary-compressed ZstdNet frame before dictionary activation");
            }
            var streamDictionary = usesDictionary ? dictionary : null;
            var dictionaryId = streamDictionary == null ? 0L : streamDictionary.id();
            if (persistentStream == null || persistentDictionaryId != dictionaryId) {
                if (persistentStream != null) persistentStream.close();
                persistentStream = new ZstdPersistentStreamCodec(ZstdPersistentStreamCodec.DECODE_LEVEL, streamDictionary);
                persistentDictionaryId = dictionaryId;
            }
            var raw = ctx.alloc().buffer(Math.min(rawLength, 64 * 1024), rawLength);
            if (rawLength >= ASYNC_DECOMPRESSION_THRESHOLD) {
                asyncDecompressionInFlight = true;
                var wireBytes = in.readerIndex() - frameStart;
                var asyncStream = persistentStream;
                try {
                    ZstdCompressionPool.execute(() -> {
                        Throwable failure = null;
                        try {
                            asyncStream.decompress(payload, rawLength, raw);
                        } catch (Throwable error) {
                            failure = error;
                        } finally {
                            payload.release();
                        }
                        var result = failure;
                        ctx.executor().execute(() -> {
                            asyncDecompressionInFlight = false;
                            if (result != null || !ctx.channel().isActive()) {
                                raw.release();
                                if (result != null) {
                                    ctx.fireExceptionCaught(result);
                                }
                                restoreAutoRead(ctx);
                                return;
                            }
                            stats.inbound(rawLength, wireBytes);
                            stats.inboundSample(raw);
                            ctx.fireChannelRead(raw);
                            restoreAutoRead(ctx);
                            // Re-enter this decoder without sending the signal through upstream handlers.
                            try {
                                super.channelRead(ctx, io.netty.buffer.Unpooled.EMPTY_BUFFER);
                            } catch (Exception error) {
                                ctx.fireExceptionCaught(error);
                                ctx.close();
                            }
                        });
                    });
                } catch (RejectedExecutionException rejected) {
                    asyncDecompressionInFlight = false;
                    try {
                        persistentStream.decompress(payload, rawLength, raw);
                    } catch (Exception error) {
                        raw.release();
                        throw error;
                    } finally {
                        payload.release();
                    }
                    stats.inbound(rawLength, wireBytes);
                    stats.inboundSample(raw);
                    out.add(raw);
                }
                return;
            }
            try {
                persistentStream.decompress(payload, rawLength, raw);
            } catch (Exception error) {
                raw.release();
                throw error;
            } finally {
                payload.release();
            }
            stats.inbound(rawLength, in.readerIndex() - frameStart);
            stats.inboundSample(raw);
            out.add(raw);
        }
    }

    @Override
    public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
        try {
            super.channelInactive(ctx);
        } finally {
            if (dictionarySession != null) dictionarySession.disconnected();
            closePersistentStream();
        }
    }

    @Override
    public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
        if (dictionarySession != null) dictionarySession.disconnected();
        try { closePersistentStream(); } catch (IOException ignored) { }
        ctx.fireExceptionCaught(cause);
        ctx.close();
    }

    private static void restoreAutoRead(io.netty.channel.ChannelHandlerContext ctx) {
        if (ctx.channel().isActive() && !ctx.channel().config().isAutoRead()) {
            ctx.channel().config().setAutoRead(true);
        }
    }

    private void closePersistentStream() throws IOException {
        if (ownsPersistentStream && persistentStream != null) {
            persistentStream.close();
            persistentStream = null;
        }
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
}
