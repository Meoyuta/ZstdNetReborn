package mys.zstdnet.reborn.core.protocol;

import mys.zstdnet.reborn.core.dictionary.DictionaryFixtures;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZstdFrameCodecOptimizationTest {
    @Test
    void shortPayloadsBypassCompressionAndRoundTrip() throws Exception {
        var raw = new byte[31];
        Arrays.fill(raw, (byte) 7);
        var frame = Unpooled.buffer();
        var decoded = Unpooled.buffer(raw.length);
        try (
                var encoder = new ZstdPersistentStreamCodec(9, DictionaryFixtures.dictionary());
                var decoder = new ZstdPersistentStreamCodec(9, DictionaryFixtures.dictionary())
        ) {
            ZstdFrameCodec.writeFrame(Unpooled.wrappedBuffer(raw), encoder, true, frame);
            assertTrue(frame.readableBytes() > raw.length);
            assertEquals(raw.length, ZstdFrameCodec.readVarInt(frame));
            assertEquals(0, ZstdFrameCodec.readVarInt(frame));
            frame.readerIndex(0);
            ZstdFrameCodec.readFrame(frame, decoder, decoded);
            var actual = new byte[decoded.readableBytes()];
            decoded.readBytes(actual);
            assertArrayEquals(raw, actual);
        } finally {
            frame.release();
            decoded.release();
        }
    }

}
