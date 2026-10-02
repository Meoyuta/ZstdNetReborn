package mys.zstdnet.reborn.core.protocol;

import org.junit.jupiter.api.Test;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZstdPersistentStreamCodecTest {
    @Test
    void retainsHistoryAcrossPacketBoundaries() throws Exception {
        try (var encoder = new ZstdPersistentStreamCodec(3, null);
             var decoder = new ZstdPersistentStreamCodec(3, null)) {
            var first = new byte[8192];
            var second = new byte[8192];
            Arrays.fill(first, (byte) 'a');
            System.arraycopy(first, 0, second, 0, first.length);
            second[second.length - 1] = 'b';
            var firstPayload = encoder.compress(first);
            var secondPayload = encoder.compress(second);
            assertArrayEquals(first, decoder.decompress(firstPayload, first.length));
            assertArrayEquals(second, decoder.decompress(secondPayload, second.length));
            org.junit.jupiter.api.Assertions.assertTrue(secondPayload.length < firstPayload.length);
        }
    }

    @Test
    void closedCodecRejectsFurtherPackets() throws Exception {
        var codec = new ZstdPersistentStreamCodec(3, null);
        codec.close();
        assertThrows(java.io.IOException.class, () -> codec.compress(new byte[]{1}));
    }

    @Test
    void byteBufPathRoundTripsWithoutTakingOwnershipOfInputs() throws Exception {
        var raw = new byte[8192];
        Arrays.fill(raw, (byte) 'q');
        ByteBuf input = Unpooled.directBuffer(raw.length).writeBytes(raw);
        ByteBuf payload = Unpooled.buffer();
        ByteBuf decoded = Unpooled.buffer(raw.length);
        try (var encoder = new ZstdPersistentStreamCodec(3, null);
             var decoder = new ZstdPersistentStreamCodec(3, null)) {
            encoder.compress(input, payload);
            decoder.decompress(payload, raw.length, decoded);
            var actual = new byte[decoded.readableBytes()];
            decoded.readBytes(actual);
            assertArrayEquals(raw, actual);
            assertEquals(1, input.refCnt());
            assertEquals(1, payload.refCnt());
        } finally {
            input.release();
            payload.release();
            decoded.release();
        }
    }

    @Test
    void byteBufPathSupportsMaximumFrameSize() throws Exception {
        var raw = new byte[ZstdFrameCodec.MAX_FRAME_BYTES];
        ByteBuf input = Unpooled.directBuffer(raw.length).writeBytes(raw);
        ByteBuf payload = Unpooled.buffer();
        ByteBuf decoded = Unpooled.buffer(raw.length);
        try (var encoder = new ZstdPersistentStreamCodec(3, null);
             var decoder = new ZstdPersistentStreamCodec(3, null)) {
            encoder.compress(input, payload);
            decoder.decompress(payload, raw.length, decoded);
            assertEquals(raw.length, decoded.readableBytes());
            assertEquals(1, input.refCnt());
            assertEquals(1, payload.refCnt());
        } finally {
            input.release();
            payload.release();
            decoded.release();
        }
    }
}
