package mys.zstdnet.reborn.core.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.util.function.IntSupplier;

public final class ZstdNettyPipeline {
    public static final String INBOUND_HANDLER = "zstdnet-inbound";
    public static final String OUTBOUND_HANDLER = "zstdnet-outbound";
    public static final String CONTROL_HANDLER = "zstdnet-control";

    private static final String SPLITTER = "splitter";
    private static final String PREPENDER = "prepender";
    private static final String DECRYPT = "decrypt";
    private static final String ENCRYPT = "encrypt";
    private static final String PACKET_HANDLER = "packet_handler";

    private ZstdNettyPipeline() {
    }

    public static void install(ChannelPipeline pipeline, int level, boolean sendMagic, ZstdFrameStats stats) {
        install(pipeline, level, sendMagic, stats, ZstdDictionarySession.withoutDictionary());
    }

    public static void install(
        ChannelPipeline pipeline,
        int level,
        boolean sendMagic,
        ZstdFrameStats stats,
        ZstdDictionarySession dictionarySession
    ) {
        install(pipeline, () -> level, sendMagic, stats, dictionarySession);
    }

    public static void install(
        ChannelPipeline pipeline,
        IntSupplier level,
        boolean sendMagic,
        ZstdFrameStats stats,
        ZstdDictionarySession dictionarySession
    ) {
        if (pipeline.get(INBOUND_HANDLER) == null) {
            addInbound(pipeline, new ZstdNettyDecoder(stats, dictionarySession));
        }
        if (pipeline.get(OUTBOUND_HANDLER) == null) {
            addOutbound(pipeline, new ZstdNettyEncoder(level, sendMagic, stats, dictionarySession));
        }
        if (pipeline.get(CONTROL_HANDLER) == null) {
            if (pipeline.get(PACKET_HANDLER) != null) {
                pipeline.addBefore(PACKET_HANDLER, CONTROL_HANDLER, new ControlHandler());
            } else {
                pipeline.addLast(CONTROL_HANDLER, new ControlHandler());
            }
        }
        reposition(pipeline);
    }

    public static void reposition(ChannelPipeline pipeline) {
        if (pipeline.get(INBOUND_HANDLER) != null) {
            if (pipeline.get(DECRYPT) != null) {
                moveAfter(pipeline, INBOUND_HANDLER, DECRYPT);
            } else if (pipeline.get(SPLITTER) != null) {
                moveBefore(pipeline, INBOUND_HANDLER, SPLITTER);
            }
        }
        if (pipeline.get(OUTBOUND_HANDLER) != null) {
            if (pipeline.get(ENCRYPT) != null) {
                moveAfter(pipeline, OUTBOUND_HANDLER, ENCRYPT);
            } else if (pipeline.get(PREPENDER) != null) {
                moveBefore(pipeline, OUTBOUND_HANDLER, PREPENDER);
            }
        }
    }

    private static void addInbound(ChannelPipeline pipeline, ChannelHandler handler) {
        if (pipeline.get(DECRYPT) != null) {
            pipeline.addAfter(DECRYPT, INBOUND_HANDLER, handler);
        } else if (pipeline.get(SPLITTER) != null) {
            pipeline.addBefore(SPLITTER, INBOUND_HANDLER, handler);
        } else {
            pipeline.addFirst(INBOUND_HANDLER, handler);
        }
    }

    private static void addOutbound(ChannelPipeline pipeline, ChannelHandler handler) {
        if (pipeline.get(ENCRYPT) != null) {
            pipeline.addAfter(ENCRYPT, OUTBOUND_HANDLER, handler);
        } else if (pipeline.get(PREPENDER) != null) {
            pipeline.addBefore(PREPENDER, OUTBOUND_HANDLER, handler);
        } else {
            pipeline.addFirst(OUTBOUND_HANDLER, handler);
        }
    }

    private static void moveAfter(ChannelPipeline pipeline, String name, String baseName) {
        var names = pipeline.names();
        var current = names.indexOf(name);
        var base = names.indexOf(baseName);
        if (current < 0 || base < 0 || current == base + 1) {
            return;
        }

        var currentHandler = pipeline.get(name);
        var handler = copyForMove(currentHandler);
        pipeline.remove(name);
        pipeline.addAfter(baseName, name, handler);
    }

    private static void moveBefore(ChannelPipeline pipeline, String name, String baseName) {
        var names = pipeline.names();
        var current = names.indexOf(name);
        var base = names.indexOf(baseName);
        if (current < 0 || base < 0 || current == base - 1) {
            return;
        }

        var currentHandler = pipeline.get(name);
        var handler = copyForMove(currentHandler);
        pipeline.remove(name);
        pipeline.addBefore(baseName, name, handler);
    }

    private static ChannelHandler copyForMove(ChannelHandler handler) {
        if (handler instanceof ZstdNettyDecoder decoder) {
            return decoder.copyForMove();
        }
        if (handler instanceof ZstdNettyEncoder encoder) {
            return encoder.copyForMove();
        }
        return handler;
    }

    private static final class ControlHandler extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            reposition(ctx.pipeline());
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            reposition(ctx.pipeline());
            super.write(ctx, msg, promise);
        }
    }
}
