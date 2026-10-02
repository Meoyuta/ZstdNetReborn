package mys.zstdnet.reborn.core.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.util.logging.Level;
import java.util.logging.Logger;

    /** Records the terminal state and close initiator of a ZstdNet TCP channel. */
public final class ZstdConnectionDiagnostics extends ChannelDuplexHandler {
    private static final Logger LOGGER = Logger.getLogger("ZstdNet");

    private String lastInbound;
    private String lastOutbound;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().closeFuture().addListener(future -> log(ctx, "close-complete"));
        log(ctx, "diagnostics-installed");
        super.handlerAdded(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log(ctx, "active");
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        lastInbound = messageName(msg);
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        lastOutbound = messageName(msg);
        super.write(ctx, msg, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log(ctx, "inactive");
        super.channelInactive(ctx);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        log(ctx, "close-request");
        super.close(ctx, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        log(ctx, "disconnect-request");
        super.disconnect(ctx, promise);
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        log(ctx, "deregister-request");
        super.deregister(ctx, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log(ctx, "exception " + cause.getClass().getName() + ": " + String.valueOf(cause.getMessage()));
        LOGGER.log(Level.FINE, "ZstdNet channel exception details", cause);
        super.exceptionCaught(ctx, cause);
    }

    private void log(ChannelHandlerContext ctx, String event) {
        var channel = ctx.channel();
        var message = "connection=" + channel.id().asShortText()
            + " event=" + event
            + " remote=" + channel.remoteAddress()
            + " open=" + channel.isOpen()
            + " active=" + channel.isActive()
            + " lastInbound=" + lastInbound
            + " lastOutbound=" + lastOutbound
            + " pipeline=" + ctx.pipeline().names();
        if (event.equals("close-request") || event.equals("close-complete") || event.equals("inactive")) {
            LOGGER.info(message);
        } else {
            LOGGER.fine(message);
        }
    }

    private static String messageName(Object message) {
        return message == null ? "null" : message.getClass().getName();
    }
}
