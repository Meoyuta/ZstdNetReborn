package mys.zstdnet.reborn.core.netty;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;

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
        assertNotNull(pipeline.get(ZstdNettyPipeline.DIAGNOSTICS_HANDLER));
        ZstdNettyPipeline.install(pipeline, 3, true, ZstdFrameStats.NONE);
        assertEquals(1, pipeline.names().stream()
            .filter(ZstdNettyPipeline.DIAGNOSTICS_HANDLER::equals).count());
    }

    @Test
    void skipsIndependentDatagramChannels() {
        var channel = new NioDatagramChannel();
        var group = new NioEventLoopGroup(1);
        try {
            group.register(channel).syncUninterruptibly();
            ZstdNettyPipeline.install(channel.pipeline(), 3, true, ZstdFrameStats.NONE);
            assertNull(channel.pipeline().get(ZstdNettyPipeline.INBOUND_HANDLER));
            assertNull(channel.pipeline().get(ZstdNettyPipeline.OUTBOUND_HANDLER));
            assertNull(channel.pipeline().get(ZstdNettyPipeline.CONTROL_HANDLER));
            assertNull(channel.pipeline().get(ZstdNettyPipeline.DIAGNOSTICS_HANDLER));
        } finally {
            channel.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
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
        server.writeOutbound(Unpooled.wrappedBuffer(first));
        server.checkException();
        client.writeInbound(awaitOutbound(server));
        client.checkException();
        assertInbound(client, first);

        server.pipeline().addLast("encrypt", new ChannelOutboundHandlerAdapter());
        ZstdNettyPipeline.reposition(server.pipeline());

        var second = new byte[4096];
        System.arraycopy(first, 0, second, 0, first.length);
        second[second.length - 1] = 'b';
        server.writeOutbound(Unpooled.wrappedBuffer(second));
        server.checkException();
        client.writeInbound(awaitOutbound(server));
        client.checkException();
        assertInbound(client, second);

        server.finishAndReleaseAll();
        client.finishAndReleaseAll();
    }

    @Test
    void encodesMagicAndRoundTripsFrame() throws Exception {
        var raw = new byte[]{0x05, 0x00, 0x01, 0x02, 0x03, 0x04};
        var encoder = new EmbeddedChannel(new ZstdNettyEncoder(3, true, ZstdFrameStats.NONE));

        encoder.writeOutbound(Unpooled.wrappedBuffer(raw));
        encoder.checkException();
        ByteBuf encoded = awaitOutbound(encoder);
        try {
            for (byte magicByte : ZstdFrameCodec.MAGIC) {
                assertEquals(magicByte, encoded.readByte());
            }

            var decoder = new EmbeddedChannel(new ZstdNettyDecoder(ZstdFrameStats.NONE));
            assertTrue(decoder.writeInbound(encoded.retainedSlice()));
            decoder.checkException();
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
    void asyncDecompressionKeepsFrameOrder() throws Exception {
        var first = new byte[96 * 1024];
        var second = new byte[96 * 1024];
        java.util.Arrays.fill(first, (byte) 'a');
        java.util.Arrays.fill(second, (byte) 'b');
        var decoder = new EmbeddedChannel(new ZstdNettyDecoder(ZstdFrameStats.NONE));
        try {
            var firstWire = Unpooled.buffer();
            var secondWire = Unpooled.buffer();
            try (var stream = new mys.zstdnet.reborn.core.protocol.ZstdPersistentStreamCodec(9, null)) {
                ZstdFrameCodec.writeFrame(Unpooled.wrappedBuffer(first), stream, false, firstWire);
                ZstdFrameCodec.writeFrame(Unpooled.wrappedBuffer(second), stream, false, secondWire);
            }
            var combined = Unpooled.buffer(firstWire.readableBytes() + secondWire.readableBytes());
            combined.writeBytes(firstWire).writeBytes(secondWire);
            firstWire.release();
            secondWire.release();
            decoder.writeInbound(combined);
            decoder.checkException();
            ByteBuf actualFirst = awaitInbound(decoder);
            ByteBuf actualSecond = awaitInbound(decoder);
            try {
                assertEquals(first.length, actualFirst.readableBytes());
                assertEquals(second.length, actualSecond.readableBytes());
                assertEquals((byte) 'a', actualFirst.getByte(actualFirst.readerIndex()));
                assertEquals((byte) 'b', actualSecond.getByte(actualSecond.readerIndex()));
            } finally {
                actualFirst.release();
                actualSecond.release();
            }
        } finally {
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    void inboundBackpressureRestoresAutoReadAfterLargeFrame() throws Exception {
        var raw = new byte[96 * 1024];
        java.util.Arrays.fill(raw, (byte) 'p');
        var decoder = new EmbeddedChannel(new ZstdNettyDecoder(ZstdFrameStats.NONE));
        try {
            var wire = encodeFrame(raw);
            decoder.writeInbound(wire);
            decoder.checkException();
            awaitInbound(decoder).release();
            decoder.runPendingTasks();
            assertTrue(decoder.config().isAutoRead());
        } finally {
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    void decompressionUsesBoundedSharedPool() {
        assertTrue(ZstdCompressionPool.maximumThreads() >= 4);
        assertTrue(ZstdCompressionPool.maximumThreads() <= Runtime.getRuntime().availableProcessors()
            || Runtime.getRuntime().availableProcessors() < 4);
    }

    @Test
    void decodesUncompressedSmallFrame() throws Exception {
        var raw = new byte[]{1, 2, 3, 4, 5};
        var frame = Unpooled.buffer();
        try (var codec = new mys.zstdnet.reborn.core.protocol.ZstdPersistentStreamCodec(3, null)) {
            ZstdFrameCodec.writeFrame(Unpooled.wrappedBuffer(raw), codec, false, frame);
        }
        var decoder = new EmbeddedChannel(new ZstdNettyDecoder(ZstdFrameStats.NONE));
        try {
            assertTrue(decoder.writeInbound(frame.retain()));
            decoder.checkException();
            ByteBuf decoded = decoder.readInbound();
            try {
                assertNotNull(decoded);
                var actual = new byte[decoded.readableBytes()];
                decoded.readBytes(actual);
                assertArrayEquals(raw, actual);
            } finally {
                if (decoded != null) decoded.release();
            }
        } finally {
            frame.release();
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    void encoderReadsCompressionLevelForEachExistingConnectionWrite() throws Exception {
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
        encoder.writeOutbound(Unpooled.wrappedBuffer(raw));
        encoder.checkException();
        ByteBuf first = awaitOutbound(encoder);
        first.release();
        level.set(22);
        encoder.writeOutbound(Unpooled.wrappedBuffer(raw));
        encoder.checkException();
        ByteBuf second = awaitOutbound(encoder);
        second.release();
        assertEquals(2, calls.get(), "the live level supplier must be queried for every frame");
        encoder.finishAndReleaseAll();
    }

    @Test
    void compressionLevelChangeResetsPeerPersistentStreamBeforeNextFrame() throws Exception {
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
            server.writeOutbound(Unpooled.wrappedBuffer(raw));
            server.checkException();
            client.writeInbound(awaitOutbound(server));
            client.checkException();
            assertInbound(client, raw);

            level.set(9);
            raw[raw.length - 1] = 'x';
            server.writeOutbound(Unpooled.wrappedBuffer(raw));
            server.checkException();
            client.writeInbound(awaitOutbound(server));
            client.checkException();
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
        channel.checkException();
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();

        assertNull(pipeline.get("compress"));
        assertNull(pipeline.get("decompress"));
    }

    @Test
    void batchesOneHundredSmallPacketsWithoutChangingBytes() throws Exception {
        var packets = new byte[100][20];
        for (var i = 0; i < packets.length; i++) {
            for (var j = 0; j < packets[i].length; j++) packets[i][j] = (byte) (i * 3 + j);
        }

        var individual = new EmbeddedChannel(new ZstdNettyEncoder(9, false, ZstdFrameStats.NONE));
        var individualWireBytes = 0;
        try {
            for (var packet : packets) {
                individual.writeOutbound(Unpooled.wrappedBuffer(packet));
                var encoded = awaitOutbound(individual);
                individualWireBytes += encoded.readableBytes();
                encoded.release();
            }
        } finally {
            individual.finishAndReleaseAll();
        }

        var batchedEncoder = new ZstdNettyEncoder(9, false, ZstdFrameStats.NONE);
        var batched = new EmbeddedChannel(batchedEncoder);
        var decoder = new EmbeddedChannel(new ZstdNettyDecoder(ZstdFrameStats.NONE));
        var wireBytes = 0;
        try {
            for (var packet : packets) batched.writeOutbound(Unpooled.wrappedBuffer(packet));
            batched.checkException();
            long deadline = System.nanoTime() + 5_000_000_000L;
            long idleSince = 0L;
            while (System.nanoTime() < deadline) {
                batched.runPendingTasks();
                batched.runScheduledPendingTasks();
                ByteBuf encoded;
                var produced = false;
                while ((encoded = batched.readOutbound()) != null) {
                    produced = true;
                    wireBytes += encoded.readableBytes();
                    decoder.writeInbound(encoded);
                    decoder.checkException();
                }
                if (batchedEncoder.isIdleForMove()) {
                    if (idleSince == 0L) idleSince = System.nanoTime();
                    if (System.nanoTime() - idleSince >= 20_000_000L) break;
                } else {
                    idleSince = 0L;
                }
                if (!produced) Thread.sleep(1L);
            }
            var expected = new ByteArrayOutputStream();
            for (var packet : packets) expected.write(packet);
            var actual = new ByteArrayOutputStream();
            ByteBuf decoded;
            while ((decoded = decoder.readInbound()) != null) {
                try {
                    var bytes = new byte[decoded.readableBytes()];
                    decoded.readBytes(bytes);
                    actual.write(bytes);
                } finally {
                    decoded.release();
                }
            }
            assertArrayEquals(expected.toByteArray(), actual.toByteArray());
            assertTrue(wireBytes < individualWireBytes / 2,
                "batched wire bytes=" + wireBytes + " individual=" + individualWireBytes);
        } finally {
            try {
                batched.finishAndReleaseAll();
            } catch (Exception ignored) {
                // The aggregate promise is completed as the EmbeddedChannel closes.
            }
            decoder.finishAndReleaseAll();
        }
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

    private static ByteBuf awaitOutbound(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        ByteBuf result;
        while ((result = channel.readOutbound()) == null && System.nanoTime() < deadline) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            channel.checkException();
            Thread.yield();
        }
        assertNotNull(result, "asynchronous compressed output did not arrive");
        return result;
    }

    private static ByteBuf awaitInbound(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        ByteBuf result;
        while ((result = channel.readInbound()) == null && System.nanoTime() < deadline) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            channel.checkException();
            Thread.yield();
        }
        assertNotNull(result, "asynchronous decompressed output did not arrive");
        return result;
    }

    private static ByteBuf encodeFrame(byte[] raw) throws Exception {
        var out = Unpooled.buffer();
        try (var stream = new mys.zstdnet.reborn.core.protocol.ZstdPersistentStreamCodec(9, null)) {
            ZstdFrameCodec.writeFrame(Unpooled.wrappedBuffer(raw), stream, false, out);
        }
        return out;
    }

}
