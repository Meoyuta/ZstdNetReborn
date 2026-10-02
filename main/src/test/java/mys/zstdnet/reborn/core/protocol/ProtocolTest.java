package mys.zstdnet.reborn.core.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
        var out = new ByteArrayOutputStream();
        PacketIo.writePacket(out, payload);
        assertArrayEquals(payload, PacketIo.readPacket(new ByteArrayInputStream(out.toByteArray())));
        assertArrayEquals(payload, PacketIo.extractPacketPayload(out.toByteArray()));
    }

    @Test
    void zstdFrameStoresSmallExpandedPayloadRaw() throws Exception {
        var payload = new byte[]{0x01, 0x02, 0x03};
        var frame = ZstdFrameCodec.compressFrame(payload, 9);
        var rawLength = VarIntCodec.read(frame, 0, frame.length);
        assertNotNull(rawLength);
        var storedLength = VarIntCodec.read(frame, rawLength.next(), frame.length);
        assertNotNull(storedLength);

        assertEquals(payload.length, rawLength.value());
        assertEquals(0, storedLength.value());
        assertArrayEquals(payload, ZstdFrameCodec.readFrame(new ByteArrayInputStream(frame)));
    }

    @Test
    void zstdFrameCompressesUsefulPayload() throws Exception {
        var payload = new byte[4096];
        Arrays.fill(payload, (byte) 'A');
        var frame = ZstdFrameCodec.compressFrame(payload, 9);
        var rawLength = VarIntCodec.read(frame, 0, frame.length);
        assertNotNull(rawLength);
        var storedLength = VarIntCodec.read(frame, rawLength.next(), frame.length);
        assertNotNull(storedLength);

        assertEquals(payload.length, rawLength.value());
        assertTrue(storedLength.value() > 0);
        assertTrue(storedLength.value() < payload.length);
        assertArrayEquals(payload, ZstdFrameCodec.readFrame(new ByteArrayInputStream(frame)));
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
}
