package mys.zstdnet.reborn.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            var firstPayload = Unpooled.buffer();
            var secondPayload = Unpooled.buffer();
            var firstDecoded = Unpooled.buffer(first.length);
            var secondDecoded = Unpooled.buffer(second.length);
            try {
                encoder.compress(Unpooled.wrappedBuffer(first), firstPayload);
                encoder.compress(Unpooled.wrappedBuffer(second), secondPayload);
                decoder.decompress(firstPayload, first.length, firstDecoded);
                decoder.decompress(secondPayload, second.length, secondDecoded);
                assertBufEquals(first, firstDecoded);
                assertBufEquals(second, secondDecoded);
                assertTrue(secondPayload.readableBytes() < firstPayload.readableBytes());
            } finally {
                firstPayload.release();
                secondPayload.release();
                firstDecoded.release();
                secondDecoded.release();
            }
        }
    }

    @Test
    void closedCodecRejectsFurtherPackets() throws Exception {
        var codec = new ZstdPersistentStreamCodec(3, null);
        codec.close();
        var input = Unpooled.buffer().writeByte(1);
        var output = Unpooled.buffer();
        try {
            assertThrows(java.io.IOException.class, () -> codec.compress(input, output));
        } finally {
            input.release();
            output.release();
        }
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
            assertBufEquals(raw, decoded);
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

    @Test
    void rejectsTruncatedPayloadInsteadOfWaitingForever() throws Exception {
        try (var decoder = new ZstdPersistentStreamCodec(3, null)) {
            var payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
            var decoded = Unpooled.buffer();
            try {
                assertThrows(java.io.IOException.class, () -> decoder.decompress(payload, 4096, decoded));
            } finally {
                payload.release();
                decoded.release();
            }
        }
    }

    private static void assertBufEquals(byte[] expected, ByteBuf actual) {
        assertEquals(expected.length, actual.readableBytes());
        var bytes = new byte[expected.length];
        actual.getBytes(actual.readerIndex(), bytes);
        assertArrayEquals(expected, bytes);
    }
}
