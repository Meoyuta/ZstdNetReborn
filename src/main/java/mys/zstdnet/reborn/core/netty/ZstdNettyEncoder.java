package mys.zstdnet.reborn.core.netty;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import mys.zstdnet.reborn.core.protocol.ZstdPersistentStreamCodec;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import mys.zstdnet.reborn.core.stats.CompressionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ZstdNettyEncoder extends ChannelDuplexHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");
    private static final int MAX_BATCH_BYTES = 64 * 1024;
    private static final int FLUSH_DELAY_MILLIS = 2;
    private static final int MAX_PENDING_PACKETS = 4096;
    private static final CompressionMetrics GLOBAL_METRICS = new CompressionMetrics();
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
    private final CompressionMetrics metrics = GLOBAL_METRICS;
    private final ArrayDeque<PendingWrite> pending = new ArrayDeque<>();
    private int pendingBytes;
    private ScheduledFuture<?> scheduledFlush;

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
        return pending.isEmpty() && scheduledFlush == null;
    }

    public CompressionMetrics metrics() {
        return metrics;
    }

    public static CompressionMetrics globalMetrics() {
        return GLOBAL_METRICS;
    }

    @Override
    public void write(io.netty.channel.ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
        if (!(message instanceof ByteBuf msg)) {
            flushPending(ctx);
            ctx.write(message, promise);
            return;
        }
        if (closed || !ctx.channel().isActive()) {
            LOGGER.debug("encoder write rejected: closed={} active={}", closed, ctx.channel().isActive());
            msg.release();
            promise.tryFailure(new ClosedChannelException());
            return;
        }
        int length = msg.readableBytes();
        if (length >= MAX_BATCH_BYTES) {
            flushPending(ctx);
            emitBatch(ctx, List.of(new PendingWrite(msg, promise)));
            return;
        }
        if (pendingBytes + length > MAX_BATCH_BYTES) {
            flushPending(ctx);
        }
        pending.addLast(new PendingWrite(msg, promise));
        pendingBytes += length;
        if (pending.size() >= MAX_PENDING_PACKETS) {
            flushPending(ctx);
        }
        scheduleFlush(ctx);
    }

    @Override
    public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
        LOGGER.debug("encoder channelInactive");
        closed = true;
        cancelScheduledFlush();
        failPending(new ClosedChannelException());
        closePersistentStreamQuietly();
        super.channelInactive(ctx);
    }

    private void encode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
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
    }

    @Override
    public void flush(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
        if (pending.isEmpty()) {
            ctx.flush();
            return;
        }
        if (!ctx.channel().isWritable() || pendingBytes >= MAX_BATCH_BYTES) {
            flushPending(ctx);
            ctx.flush();
            return;
        }
        scheduleFlush(ctx);
    }

    private void flushPending(io.netty.channel.ChannelHandlerContext ctx) {
        if (pending.isEmpty()) return;
        cancelScheduledFlush();
        var batch = new ArrayList<PendingWrite>(pending);
        pending.clear();
        pendingBytes = 0;
        emitBatch(ctx, batch);
    }

    private void scheduleFlush(io.netty.channel.ChannelHandlerContext ctx) {
        if (scheduledFlush != null) return;
        scheduledFlush = ctx.executor().schedule(() -> {
            scheduledFlush = null;
            if (!closed) {
                flushPending(ctx);
                ctx.flush();
            }
        }, FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void emitBatch(io.netty.channel.ChannelHandlerContext ctx, List<PendingWrite> batch) {
        int total = batch.stream().mapToInt(value -> value.message.readableBytes()).sum();
        ByteBuf raw = ctx.alloc().buffer(Math.max(256, total), Math.max(256, total));
        for (var item : batch) {
            raw.writeBytes(item.message, item.message.readerIndex(), item.message.readableBytes());
            item.message.release();
        }
        ByteBuf encoded = ctx.alloc().buffer();
        long started = System.nanoTime();
        boolean rawReleased = false;
        try {
            encode(ctx, raw, encoded);
            raw.release();
            rawReleased = true;
            if (!encoded.isReadable()) {
                encoded.release();
                for (var item : batch) item.promise.trySuccess();
                return;
            }
            var aggregate = ctx.newPromise();
            aggregate.addListener(future -> {
                for (var item : batch) {
                    if (future.isSuccess()) item.promise.trySuccess();
                    else item.promise.tryFailure(future.cause());
                }
                if (!future.isSuccess()) ctx.close();
            });
            ctx.write(encoded, aggregate);
            metrics.recordSync(total, batch.size(), System.nanoTime() - started);
        } catch (Throwable error) {
            if (!rawReleased) raw.release();
            encoded.release();
            for (var item : batch) item.promise.tryFailure(error);
            ctx.fireExceptionCaught(error);
            ctx.close();
        }
    }

    private void failPending(Throwable error) {
        while (!pending.isEmpty()) {
            var item = pending.removeFirst();
            item.message.release();
            item.promise.tryFailure(error);
        }
        pendingBytes = 0;
    }

    private void cancelScheduledFlush() {
        if (scheduledFlush != null) {
            scheduledFlush.cancel(false);
            scheduledFlush = null;
        }
    }

    private record PendingWrite(ByteBuf message, ChannelPromise promise) {}

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
