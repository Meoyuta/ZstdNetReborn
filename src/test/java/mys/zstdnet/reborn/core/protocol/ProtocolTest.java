package mys.zstdnet.reborn.core.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {
    @Test
    void varIntRoundTrip() throws Exception {
        var values = new int[]{0, 1, 2, 127, 128, 255, 25565, 2_097_151};
        for (var value : values) {
            var encoded = VarIntCodec.encode(value);
            assertEquals(value, VarIntCodec.read(new ByteArrayInputStream(encoded)));
            var read = VarIntCodec.read(encoded, 0, encoded.length);
            assertNotNull(read);
            assertEquals(value, read.value());
            assertEquals(encoded.length, read.next());
        }
    }

    @Test
    void packetRoundTrip() throws Exception {
        var payload = "hello".getBytes(StandardCharsets.UTF_8);
        var out = Unpooled.buffer();
        try {
            PacketIo.writePacket(new ByteBufOutputStream(out), payload);
            var wire = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), wire);
            assertArrayEquals(payload, PacketIo.readPacket(new ByteArrayInputStream(wire)));
            assertArrayEquals(payload, PacketIo.extractPacketPayload(wire));
        } finally {
            out.release();
        }
    }

    @Test
    void zstdFrameStoresSmallExpandedPayloadRaw() throws Exception {
        var payload = new byte[]{0x01, 0x02, 0x03};
        var frame = Unpooled.buffer();
        var decoded = Unpooled.buffer(payload.length);
        try (var encoder = new ZstdPersistentStreamCodec(9, null);
             var decoder = new ZstdPersistentStreamCodec(9, null)) {
            ZstdFrameCodec.writeFrame(Unpooled.wrappedBuffer(payload), encoder, false, frame);
            var rawLength = ZstdFrameCodec.readVarInt(frame);
            var storedLength = ZstdFrameCodec.readVarInt(frame);
            assertEquals(payload.length, rawLength);
            assertEquals(0, storedLength);
            frame.readerIndex(0);
            ZstdFrameCodec.readFrame(frame, decoder, decoded);
            var actual = new byte[decoded.readableBytes()];
            decoded.readBytes(actual);
            assertArrayEquals(payload, actual);
        } finally {
            frame.release();
            decoded.release();
        }
    }

    @Test
    void zstdFrameCompressesUsefulPayload() throws Exception {
        var payload = new byte[4096];
        Arrays.fill(payload, (byte) 'A');
        var frame = Unpooled.buffer();
        var decoded = Unpooled.buffer(payload.length);
        try (var encoder = new ZstdPersistentStreamCodec(9, null);
             var decoder = new ZstdPersistentStreamCodec(9, null)) {
            ZstdFrameCodec.writeFrame(Unpooled.wrappedBuffer(payload), encoder, false, frame);
            var rawLength = ZstdFrameCodec.readVarInt(frame);
            var storedLength = ZstdFrameCodec.readVarInt(frame);
            assertEquals(payload.length, rawLength);
            assertTrue(storedLength > 0);
            assertTrue(storedLength < payload.length);
            frame.readerIndex(0);
            ZstdFrameCodec.readFrame(frame, decoder, decoded);
            var actual = new byte[decoded.readableBytes()];
            decoded.readBytes(actual);
            assertArrayEquals(payload, actual);
        } finally {
            frame.release();
            decoded.release();
        }
    }

    @Test
    void parsesAndRewritesHandshake() {
        var host = "example.org".getBytes(StandardCharsets.UTF_8);
        var payload = ByteArrayOps.concat(
            VarIntCodec.encode(0),
            VarIntCodec.encode(999),
            VarIntCodec.encode(host.length),
            host,
            new byte[]{(byte) (25565 >>> 8), (byte) 25565},
            VarIntCodec.encode(2)
        );

        var parsed = HandshakePacket.parse(payload);
        assertNotNull(parsed);
        assertEquals("example.org", parsed.host());
        assertEquals(25565, parsed.port());
        assertEquals(2, parsed.nextState());

        var rewritten = HandshakePacket.rewriteDestination(payload, "127.0.0.1", 25566);
        var rewrittenParsed = HandshakePacket.parse(rewritten);
        assertNotNull(rewrittenParsed);
        assertEquals("127.0.0.1", rewrittenParsed.host());
        assertEquals(25566, rewrittenParsed.port());
        assertEquals(2, rewrittenParsed.nextState());
    }

    private static final class ByteBufOutputStream extends OutputStream {
        private final ByteBuf target;

        private ByteBufOutputStream(ByteBuf target) {
            this.target = target;
        }

        @Override
        public void write(int value) {
            target.writeByte(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            target.writeBytes(bytes, offset, length);
        }
    }
}
