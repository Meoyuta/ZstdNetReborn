package mys.zstdnet.reborn.core.netty;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import mys.zstdnet.reborn.core.protocol.ZstdPersistentStreamCodec;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ZstdNettyEncoder extends ChannelDuplexHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");
    private static final QueueLimits NORMAL_QUEUE_LIMITS = new QueueLimits(128, 32L * 1024 * 1024);
    private static final QueueLimits JOIN_QUEUE_LIMITS = new QueueLimits(256, 256L * 1024 * 1024);
    private static final long JOIN_BURST_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int WORKER_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final ThreadPoolExecutor COMPRESSORS = new ThreadPoolExecutor(
        WORKER_COUNT, WORKER_COUNT, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512), task -> {
            var thread = new Thread(task, "zstdnet-compress-worker");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy()
    );
    private final IntSupplier level;
    private final boolean sendMagic;
    private final ZstdFrameStats stats;
    private final ZstdDictionarySession dictionarySession;
    private final QueueLimits normalQueueLimits;
    private final QueueLimits joinQueueLimits;
    private long queueWindowStartedNanos;
    private boolean magicSent;
    private boolean streamHeaderSent;
    private ZstdPersistentStreamCodec persistentStream;
    private long persistentDictionaryId = Long.MIN_VALUE;
    private int persistentLevel = Integer.MIN_VALUE;
    private boolean ownsPersistentStream = true;
    private final ArrayDeque<PendingWrite> pendingWrites = new ArrayDeque<>();
    private long pendingBytes;
    private boolean compressionActive;
    private boolean closeWhenIdle;
    private boolean closed;
    private boolean retryScheduled;
    private PendingWrite activeWrite;

    public ZstdNettyEncoder(int level, boolean sendMagic, ZstdFrameStats stats) {
        this(() -> level, sendMagic, stats, null);
    }

    public ZstdNettyEncoder(int level, boolean sendMagic, ZstdFrameStats stats, ZstdDictionarySession dictionarySession) {
        this(() -> level, sendMagic, stats, dictionarySession);
    }

    public ZstdNettyEncoder(IntSupplier level, boolean sendMagic, ZstdFrameStats stats,
                            ZstdDictionarySession dictionarySession) {
        this(level, sendMagic, stats, dictionarySession, NORMAL_QUEUE_LIMITS, JOIN_QUEUE_LIMITS);
    }

    ZstdNettyEncoder(IntSupplier level, boolean sendMagic, ZstdFrameStats stats,
                     ZstdDictionarySession dictionarySession, QueueLimits normalQueueLimits,
                     QueueLimits joinQueueLimits) {
        this.level = level;
        this.sendMagic = sendMagic;
        this.stats = stats == null ? ZstdFrameStats.NONE : stats;
        this.dictionarySession = dictionarySession;
        this.normalQueueLimits = normalQueueLimits;
        this.joinQueueLimits = joinQueueLimits;
        this.queueWindowStartedNanos = System.nanoTime();
    }

    ZstdNettyEncoder copyForMove() {
        var copy = new ZstdNettyEncoder(level, sendMagic, stats, dictionarySession);
        copy.queueWindowStartedNanos = queueWindowStartedNanos;
        copy.magicSent = magicSent;
        copy.streamHeaderSent = streamHeaderSent;
        copy.persistentStream = persistentStream;
        copy.persistentDictionaryId = persistentDictionaryId;
        copy.persistentLevel = persistentLevel;
        ownsPersistentStream = false;
        return copy;
    }

    boolean isIdleForMove() {
        return !compressionActive && pendingWrites.isEmpty();
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
        int bytes = msg.readableBytes();
        boolean joinBurst = isJoinBurstWindow(System.nanoTime() - queueWindowStartedNanos);
        QueueLimits limits = joinBurst ? joinQueueLimits : normalQueueLimits;
        if (!queueWithinLimits(pendingWrites.size(), pendingBytes, bytes, limits)) {
            LOGGER.warn("dropping outbound packet because compression queue limit was exceeded; "
                    + "TCP channel remains open: channel={} remote={} phase={} droppedPackets=1 "
                    + "droppedRawBytes={} packets={} queuedRawBytes={} packetLimit={} rawByteLimit={} "
                    + "compressionActive={} pipeline={}",
                ctx.channel().id().asShortText(), ctx.channel().remoteAddress(),
                joinBurst ? "join-burst" : "normal", bytes, pendingWrites.size(), pendingBytes,
                limits.maxPackets(), limits.maxBytes(), compressionActive, ctx.pipeline().names());
            msg.release();
            promise.tryFailure(new IllegalStateException("ZstdNet compression queue limit exceeded"));
            return;
        }
        pendingWrites.addLast(new PendingWrite(msg, promise, bytes));
        pendingBytes += bytes;
        drainCompressionQueue(ctx);
    }

    static boolean queueWithinLimits(int packetCount, long queuedBytes, int newMessageBytes) {
        return queueWithinLimits(packetCount, queuedBytes, newMessageBytes, NORMAL_QUEUE_LIMITS);
    }

    static boolean queueWithinLimits(int packetCount, long queuedBytes, int newMessageBytes, QueueLimits limits) {
        return packetCount < limits.maxPackets() && newMessageBytes >= 0
            && queuedBytes + newMessageBytes <= limits.maxBytes();
    }

    static boolean isJoinBurstWindow(long elapsedNanos) {
        return elapsedNanos >= 0L && elapsedNanos < JOIN_BURST_WINDOW_NANOS;
    }

    static QueueLimits normalQueueLimits() {
        return NORMAL_QUEUE_LIMITS;
    }

    static QueueLimits joinQueueLimits() {
        return JOIN_QUEUE_LIMITS;
    }

    private void drainCompressionQueue(io.netty.channel.ChannelHandlerContext ctx) {
        if (compressionActive || pendingWrites.isEmpty() || !ctx.channel().isActive()) return;
        PendingWrite pending = pendingWrites.removeFirst();
        activeWrite = pending;
        compressionActive = true;
        try {
            COMPRESSORS.execute(() -> {
                ByteBuf encoded = ctx.alloc().buffer();
                Throwable failure = null;
                try {
                    encode(ctx, pending.message(), encoded);
                } catch (Throwable error) {
                    failure = error;
                }
                Throwable result = failure;
                try {
                    ctx.executor().execute(() -> {
                        completeOnEventLoop(ctx, pending, encoded, result);
                    });
                } catch (RuntimeException rejected) {
                    encoded.release();
                    pending.message().release();
                    pending.promise().tryFailure(result == null ? rejected : result);
                    closePersistentStreamQuietly();
                }
            });
        } catch (RejectedExecutionException rejected) {
            pendingWrites.addFirst(pending);
            activeWrite = null;
            compressionActive = false;
            scheduleRetry(ctx);
        } catch (RuntimeException rejected) {
            activeWrite = null;
            compressionActive = false;
            pending.message().release();
            pendingBytes -= pending.bytes();
            pending.promise().tryFailure(rejected);
            if (!closed) ctx.fireExceptionCaught(rejected);
        }
    }

    private void completeOnEventLoop(io.netty.channel.ChannelHandlerContext ctx, PendingWrite pending,
                                     ByteBuf encoded, Throwable result) {
        pending.message().release();
        pendingBytes -= pending.bytes();
        activeWrite = null;
        compressionActive = false;
        if (closed || !ctx.channel().isActive()) {
            encoded.release();
            pending.promise().tryFailure(new ClosedChannelException());
            if (closeWhenIdle) closePersistentStreamQuietly();
            return;
        }
        if (result != null) {
            encoded.release();
            pending.promise().tryFailure(result);
            ctx.fireExceptionCaught(result);
        } else if (encoded.isReadable()) {
            ctx.write(encoded, pending.promise());
            ctx.flush();
        } else {
            encoded.release();
            pending.promise().trySuccess();
        }
        if (closeWhenIdle) closePersistentStreamQuietly();
        drainCompressionQueue(ctx);
    }

    private void scheduleRetry(io.netty.channel.ChannelHandlerContext ctx) {
        if (retryScheduled || closed || !ctx.channel().isActive()) return;
        retryScheduled = true;
        ctx.executor().schedule(() -> {
            retryScheduled = false;
            drainCompressionQueue(ctx);
        }, 1L, TimeUnit.MILLISECONDS);
    }

    @Override
    public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
        LOGGER.debug("encoder channelInactive pending={} pendingBytes={} activeWrite={}",
            pendingWrites.size(), pendingBytes, activeWrite != null);
        closed = true;
        failPendingWrites();
        if (compressionActive) closeWhenIdle = true;
        else closePersistentStreamQuietly();
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
            if (persistentStream != null && dictionarySession != null) {
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
    public void handlerRemoved(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
        closed = true;
        failPendingWrites();
        if (compressionActive) closeWhenIdle = true;
        else closePersistentStreamQuietly();
        super.handlerRemoved(ctx);
    }

    private void failPendingWrites() {
        while (!pendingWrites.isEmpty()) {
            var pending = pendingWrites.removeFirst();
            pending.message().release();
            pendingBytes -= pending.bytes();
            pending.promise().tryFailure(new ClosedChannelException());
        }
        pendingBytes = activeWrite == null ? 0L : activeWrite.bytes();
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

    private record PendingWrite(ByteBuf message, ChannelPromise promise, int bytes) {}

    record QueueLimits(int maxPackets, long maxBytes) {}
}
