package mys.zstdnet.reborn.core.netty;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import mys.zstdnet.reborn.core.protocol.VarIntCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.MessageToByteEncoder;
import mys.zstdnet.reborn.core.protocol.ZstdPersistentStreamCodec;
import java.util.function.IntSupplier;

public final class ZstdNettyEncoder extends MessageToByteEncoder<ByteBuf> {
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

    @Override
    protected void encode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
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
            byte[] control;
            while ((control = dictionarySession.pollOutboundControl()) != null) {
                var record = controlRecord(control);
                out.writeBytes(record);
                wireBytes += record.length;
            }
        }
        if (readable == 0) {
            stats.outbound(0, wireBytes);
            return;
        }
        var raw = ByteBufUtil.getBytes(msg, msg.readerIndex(), readable, false);
        mys.zstdnet.reborn.core.dictionary.ZstdDictionary dictionary = dictionarySession == null
            ? null
            : dictionarySession.activeOutboundDictionary();
        byte[] frame;
        if (dictionarySession == null) {
            frame = ZstdFrameCodec.compressFrame(raw, level.getAsInt(), dictionary);
        } else {
            var dictionaryId = dictionary == null ? 0L : dictionary.id();
            var currentLevel = Math.clamp(level.getAsInt(), 1, 22);
            if (persistentStream == null || persistentDictionaryId != dictionaryId || persistentLevel != currentLevel) {
                if (persistentStream != null && persistentDictionaryId == dictionaryId && persistentLevel != currentLevel) {
                    var reset = controlRecord(ZstdDictionarySession.streamResetControl());
                    out.writeBytes(reset);
                    wireBytes += reset.length;
                }
                if (persistentStream != null) persistentStream.close();
                persistentStream = new ZstdPersistentStreamCodec(currentLevel, dictionary);
                persistentDictionaryId = dictionaryId;
                persistentLevel = currentLevel;
            }
            var payload = persistentStream.compress(raw);
            var outFrame = new java.io.ByteArrayOutputStream(payload.length + 12);
            outFrame.write(VarIntCodec.encode(raw.length));
            outFrame.write(VarIntCodec.encode(payload.length << 1 | (dictionary == null ? 0 : 1)));
            outFrame.write(payload);
            frame = outFrame.toByteArray();
        }
        wireBytes += frame.length;
        out.writeBytes(frame);
        stats.outbound(raw.length, wireBytes);
        stats.outboundSample(raw);
    }

    @Override
    public void handlerRemoved(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
        closePersistentStream();
        super.handlerRemoved(ctx);
    }

    private void closePersistentStream() throws java.io.IOException {
        if (ownsPersistentStream && persistentStream != null) {
            persistentStream.close();
            persistentStream = null;
        }
    }

    private static byte[] controlRecord(byte[] control) throws java.io.IOException {
        var out = new java.io.ByteArrayOutputStream(control.length + 10);
        out.write(VarIntCodec.encode(0));
        out.write(VarIntCodec.encode(control.length << 1));
        out.write(control);
        return out.toByteArray();
    }
}
