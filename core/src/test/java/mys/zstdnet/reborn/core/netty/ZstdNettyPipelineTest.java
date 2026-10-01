package mys.zstdnet.reborn.core.netty;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ZstdNettyPipelineTest {
    @Test
    void installsBeforeMinecraftEncryptionExists() {
        var channel = minecraftLikeChannel();
        var pipeline = channel.pipeline();

        ZstdNettyPipeline.install(pipeline, 3, true, ZstdFrameStats.NONE);

        var names = pipeline.names();
        assertEquals(names.indexOf("splitter") - 1, names.indexOf(ZstdNettyPipeline.INBOUND_HANDLER));
        assertEquals(names.indexOf("prepender") - 1, names.indexOf(ZstdNettyPipeline.OUTBOUND_HANDLER));
        assertEquals(names.indexOf("packet_handler") - 1, names.indexOf(ZstdNettyPipeline.CONTROL_HANDLER));
    }

    @Test
    void repositionsInsideMinecraftEncryption() {
        var channel = minecraftLikeChannel();
        var pipeline = channel.pipeline();
        ZstdNettyPipeline.install(pipeline, 3, true, ZstdFrameStats.NONE);

        pipeline.addBefore("splitter", "decrypt", new ChannelInboundHandlerAdapter());
        pipeline.addBefore("prepender", "encrypt", new ChannelOutboundHandlerAdapter());
        ZstdNettyPipeline.reposition(pipeline);

        var names = pipeline.names();
        assertEquals(names.indexOf("decrypt") + 1, names.indexOf(ZstdNettyPipeline.INBOUND_HANDLER));
        assertEquals(names.indexOf("encrypt") + 1, names.indexOf(ZstdNettyPipeline.OUTBOUND_HANDLER));
        assertTrue(names.indexOf(ZstdNettyPipeline.OUTBOUND_HANDLER) < names.indexOf("prepender"));
        assertTrue(names.indexOf(ZstdNettyPipeline.INBOUND_HANDLER) < names.indexOf("splitter"));
    }

    @Test
    void repositionKeepsPersistentStreamContextAlive() throws Exception {
        var serverSession = ZstdDictionarySession.server(null);
        var server = new EmbeddedChannel();
        ZstdNettyPipeline.install(server.pipeline(), 3, false, ZstdFrameStats.NONE, serverSession);

        var clientSession = ZstdDictionarySession.client((id, bytes) -> {
            throw new java.io.IOException("No dictionary should be offered");
        }, null);
        var client = new EmbeddedChannel();
        client.pipeline().addLast(ZstdNettyPipeline.INBOUND_HANDLER,
                new ZstdNettyDecoder(ZstdFrameStats.NONE, clientSession));

        var first = new byte[4096];
        java.util.Arrays.fill(first, (byte) 'a');
        assertTrue(server.writeOutbound(Unpooled.wrappedBuffer(first)));
        client.writeInbound((ByteBuf) server.readOutbound());
        assertInbound(client, first);

        server.pipeline().addLast("encrypt", new ChannelOutboundHandlerAdapter());
        ZstdNettyPipeline.reposition(server.pipeline());

        var second = new byte[4096];
        System.arraycopy(first, 0, second, 0, first.length);
        second[second.length - 1] = 'b';
        assertTrue(server.writeOutbound(Unpooled.wrappedBuffer(second)));
        client.writeInbound((ByteBuf) server.readOutbound());
        assertInbound(client, second);

        server.finishAndReleaseAll();
        client.finishAndReleaseAll();
    }

    @Test
    void encodesMagicAndRoundTripsFrame() {
        var raw = new byte[]{0x05, 0x00, 0x01, 0x02, 0x03, 0x04};
        var encoder = new EmbeddedChannel(new ZstdNettyEncoder(3, true, ZstdFrameStats.NONE));

        assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(raw)));
        ByteBuf encoded = encoder.readOutbound();
        try {
            for (byte magicByte : ZstdFrameCodec.MAGIC) {
                assertEquals(magicByte, encoded.readByte());
            }

            var decoder = new EmbeddedChannel(new ZstdNettyDecoder(ZstdFrameStats.NONE));
            assertTrue(decoder.writeInbound(encoded.retainedSlice()));
            ByteBuf decoded = decoder.readInbound();
            try {
                var actual = new byte[decoded.readableBytes()];
                decoded.readBytes(actual);
                assertArrayEquals(raw, actual);
            } finally {
                decoded.release();
                decoder.finishAndReleaseAll();
            }
        } finally {
            encoded.release();
            encoder.finishAndReleaseAll();
        }
    }

    @Test
    void encoderReadsCompressionLevelForEachExistingConnectionWrite() {
        var level = new AtomicInteger(1);
        var calls = new AtomicInteger();
        var encoder = new EmbeddedChannel(new ZstdNettyEncoder(
            () -> {
                calls.incrementAndGet();
                return level.get();
            },
            false,
            ZstdFrameStats.NONE,
            null
        ));
        var raw = new byte[4096];
        java.util.Arrays.fill(raw, (byte) 'z');
        assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(raw)));
        ByteBuf first = encoder.readOutbound();
        first.release();
        level.set(22);
        assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(raw)));
        ByteBuf second = encoder.readOutbound();
        second.release();
        assertEquals(2, calls.get(), "the live level supplier must be queried for every frame");
        encoder.finishAndReleaseAll();
    }

    @Test
    void compressionLevelChangeResetsPeerPersistentStreamBeforeNextFrame() {
        var level = new AtomicInteger(3);
        var serverSession = ZstdDictionarySession.server(null);
        var server = new EmbeddedChannel(new ZstdNettyEncoder(level::get, false, ZstdFrameStats.NONE, serverSession));
        var clientSession = ZstdDictionarySession.client((id, bytes) -> {
            throw new java.io.IOException("No dictionary should be offered");
        }, null);
        var client = new EmbeddedChannel(new ZstdNettyDecoder(ZstdFrameStats.NONE, clientSession));
        var raw = new byte[4096];
        java.util.Arrays.fill(raw, (byte) 'z');
        try {
            assertTrue(server.writeOutbound(Unpooled.wrappedBuffer(raw)));
            client.writeInbound((ByteBuf) server.readOutbound());
            assertInbound(client, raw);

            level.set(9);
            raw[raw.length - 1] = 'x';
            assertTrue(server.writeOutbound(Unpooled.wrappedBuffer(raw)));
            client.writeInbound((ByteBuf) server.readOutbound());
            assertInbound(client, raw);
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    @Test
    void dropsMinecraftCompressionNegotiation() {
        var channel = new EmbeddedChannel();
        var pipeline = channel.pipeline();
        pipeline.addLast("compress", new ChannelOutboundHandlerAdapter());
        pipeline.addLast("decompress", new ChannelInboundHandlerAdapter());
        pipeline.addLast("packet_handler", new ChannelDuplexHandler());
        MinecraftCompressionDisabler.install(pipeline);

        assertFalse(channel.writeOutbound(new ClientboundLoginCompressionPacket()));
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();

        assertNull(pipeline.get("compress"));
        assertNull(pipeline.get("decompress"));
    }

    private static EmbeddedChannel minecraftLikeChannel() {
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
        return channel;
    }

    private static void assertInbound(EmbeddedChannel channel, byte[] expected) {
        ByteBuf actual = channel.readInbound();
        assertNotNull(actual);
        try {
            var bytes = new byte[actual.readableBytes()];
            actual.readBytes(bytes);
            assertArrayEquals(expected, bytes);
        } finally {
            actual.release();
        }
    }

    private static final class ClientboundLoginCompressionPacket {
    }
}
