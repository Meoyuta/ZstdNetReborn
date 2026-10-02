package mys.zstdnet.reborn.core.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.TimeUnit;

public final class MinecraftCompressionDisabler extends ChannelDuplexHandler {
    public static final String HANDLER_NAME = "zstdnet-vanilla-compression-disabler";

    private static final String PACKET_HANDLER = "packet_handler";
    private static final String COMPRESS = "compress";
    private static final String DECOMPRESS = "decompress";

    private MinecraftCompressionDisabler() {
    }

    public static void install(ChannelPipeline pipeline) {
        if (pipeline.get(HANDLER_NAME) != null) {
            return;
        }
        if (pipeline.get(PACKET_HANDLER) != null) {
            pipeline.addBefore(PACKET_HANDLER, HANDLER_NAME, new MinecraftCompressionDisabler());
        } else {
            pipeline.addLast(HANDLER_NAME, new MinecraftCompressionDisabler());
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        removeCompressionHandlers(ctx.pipeline());
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (isCompressionPacket(msg)) {
            promise.setSuccess();
            removeCompressionHandlers(ctx.pipeline());
            ctx.executor().execute(() -> removeCompressionHandlers(ctx.pipeline()));
            ctx.executor().schedule(() -> removeCompressionHandlers(ctx.pipeline()), 50L, TimeUnit.MILLISECONDS);
            return;
        }

        removeCompressionHandlers(ctx.pipeline());
        super.write(ctx, msg, promise);
    }

    private static boolean isCompressionPacket(Object msg) {
        return msg != null && "ClientboundLoginCompressionPacket".equals(msg.getClass().getSimpleName());
    }

    private static void removeCompressionHandlers(ChannelPipeline pipeline) {
        removeIfPresent(pipeline, COMPRESS);
        removeIfPresent(pipeline, DECOMPRESS);
    }

    private static void removeIfPresent(ChannelPipeline pipeline, String name) {
        try {
            if (pipeline.get(name) != null) {
                pipeline.remove(name);
            }
        } catch (RuntimeException ignored) {
        }
    }
}
