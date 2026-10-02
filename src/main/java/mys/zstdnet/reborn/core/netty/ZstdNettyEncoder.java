package mys.zstdnet.reborn.core.netty;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import mys.zstdnet.reborn.core.protocol.ZstdPersistentStreamCodec;
import java.nio.channels.ClosedChannelException;
import java.util.function.IntSupplier;
import mys.zstdnet.reborn.core.stats.CompressionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ZstdNettyEncoder extends ChannelDuplexHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");
    private final IntSupplier level;
    private final boolean sendMagic;
    private final ZstdFrameStats stats;
    private final ZstdDictionarySession dictionarySession;
    private boolean magicSent;
    private boolean streamHeaderSent;
    private ZstdPersistentStreamCodec persistentStream;
    private long persistentDictionaryId = Long.MIN_VALUE;
    private int persistentLevel = Integer.MIN_VALUE;
    private boolean ownsPersistentStream = true;
    private boolean closed;
    private final CompressionMetrics metrics = new CompressionMetrics();

    public ZstdNettyEncoder(int level, boolean sendMagic, ZstdFrameStats stats) {
        this(() -> level, sendMagic, stats, null);
    }

    public ZstdNettyEncoder(int level, boolean sendMagic, ZstdFrameStats stats, ZstdDictionarySession dictionarySession) {
        this(() -> level, sendMagic, stats, dictionarySession);
    }

    public ZstdNettyEncoder(IntSupplier level, boolean sendMagic, ZstdFrameStats stats,
                            ZstdDictionarySession dictionarySession) {
        this.level = level;
        this.sendMagic = sendMagic;
        this.stats = stats == null ? ZstdFrameStats.NONE : stats;
        this.dictionarySession = dictionarySession;
    }

    ZstdNettyEncoder copyForMove() {
        var copy = new ZstdNettyEncoder(level, sendMagic, stats, dictionarySession);
        copy.magicSent = magicSent;
        copy.streamHeaderSent = streamHeaderSent;
        copy.persistentStream = persistentStream;
        copy.persistentDictionaryId = persistentDictionaryId;
        copy.persistentLevel = persistentLevel;
        ownsPersistentStream = false;
        return copy;
    }

    boolean isIdleForMove() {
        return true;
    }

    public CompressionMetrics metrics() {
        return metrics;
    }

    @Override
    public void write(io.netty.channel.ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
        if (!(message instanceof ByteBuf msg)) {
            ctx.write(message, promise);
            return;
        }
        if (closed || !ctx.channel().isActive()) {
            LOGGER.debug("encoder write rejected: closed={} active={}", closed, ctx.channel().isActive());
            msg.release();
            promise.tryFailure(new ClosedChannelException());
            return;
        }
        ByteBuf encoded = ctx.alloc().buffer();
        try {
            encode(ctx, msg, encoded);
        } catch (Throwable error) {
            encoded.release();
            msg.release();
            promise.tryFailure(error);
            ctx.fireExceptionCaught(error);
            return;
        }
        msg.release();
        if (encoded.isReadable()) {
            ctx.write(encoded, promise);
            ctx.flush();
        } else {
            encoded.release();
            promise.trySuccess();
        }
    }

    @Override
    public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
        LOGGER.debug("encoder channelInactive");
        closed = true;
        closePersistentStreamQuietly();
        super.channelInactive(ctx);
    }

    private void encode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        long started = System.nanoTime();
        var readable = msg.readableBytes();
        if (readable <= 0 && (dictionarySession == null || !dictionarySession.hasPendingControl())) {
            return;
        }

        var wireBytes = 0;
        if (sendMagic && !magicSent) {
            out.writeBytes(ZstdFrameCodec.MAGIC);
            wireBytes += ZstdFrameCodec.MAGIC.length;
            magicSent = true;
        }
        if (dictionarySession != null && !streamHeaderSent) {
            ZstdStreamHeader.write(out);
            wireBytes += ZstdStreamHeader.BYTES;
            streamHeaderSent = true;
        }
        if (dictionarySession != null) {
            boolean wroteControl;
            while (true) {
                var before = out.writerIndex();
                wroteControl = dictionarySession.writeOutboundControl(out);
                if (!wroteControl) break;
                wireBytes += out.writerIndex() - before;
            }
        }
        if (readable == 0) {
            stats.outbound(0, wireBytes);
            return;
        }
        mys.zstdnet.reborn.core.dictionary.ZstdDictionary dictionary = dictionarySession == null
            ? null
            : dictionarySession.activeOutboundDictionary();
        var dictionaryId = dictionary == null ? 0L : dictionary.id();
        var currentLevel = Math.clamp(level.getAsInt(), 1, 22);
        if (persistentStream == null || persistentDictionaryId != dictionaryId || persistentLevel != currentLevel) {
            if (persistentStream != null) {
                var before = out.writerIndex();
                ZstdDictionarySession.writeStreamResetControl(out);
                wireBytes += out.writerIndex() - before;
            }
            if (persistentStream != null) persistentStream.close();
            persistentStream = new ZstdPersistentStreamCodec(currentLevel, dictionary);
            persistentDictionaryId = dictionaryId;
            persistentLevel = currentLevel;
        }
        var rawLength = msg.readableBytes();
        var frameStart = out.writerIndex();
        var payload = ctx.alloc().buffer(Math.clamp(rawLength / 2, 256, 64 * 1024));
        try {
            persistentStream.compress(msg, payload);
            var payloadLength = payload.readableBytes();
            writeVarInt(out, rawLength);
            writeVarInt(out, payloadLength << 1 | (dictionary == null ? 0 : 1));
            out.writeBytes(payload);
            wireBytes += out.writerIndex() - frameStart;
        } finally {
            payload.release();
        }
        stats.outbound(rawLength, wireBytes);
        stats.outboundSample(msg);
        metrics.recordSync(rawLength, 1, System.nanoTime() - started);
    }

    @Override
    public void handlerRemoved(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
        closed = true;
        closePersistentStreamQuietly();
        super.handlerRemoved(ctx);
    }

    private void closePersistentStreamQuietly() {
        try {
            closePersistentStream();
        } catch (java.io.IOException error) {
            // The channel is already being removed; no further stream output can be delivered.
        }
    }

    private void closePersistentStream() throws java.io.IOException {
        if (ownsPersistentStream && persistentStream != null) {
            persistentStream.close();
            persistentStream = null;
        }
    }

    private static void writeVarInt(io.netty.buffer.ByteBuf out, int value) {
        var remaining = value;
        do {
            var next = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) next |= 0x80;
            out.writeByte(next);
        } while (remaining != 0);
    }
}
