package mys.zstdnet.reborn.core.protocol;

import mys.zstdnet.reborn.core.dictionary.DictionaryFixtures;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZstdFrameCodecOptimizationTest {
    @Test
    void shortPayloadsBypassCompressionAndRoundTrip() throws Exception {
        var raw = new byte[31];
        Arrays.fill(raw, (byte) 7);
        var frame = ZstdFrameCodec.compressFrame(raw, 9, DictionaryFixtures.dictionary());

        assertTrue(frame.length > raw.length);
        assertTrue(frame[0] == raw.length);
        assertTrue(frame[1] == 0);
        assertArrayEquals(raw, ZstdFrameCodec.readFrame(new ByteArrayInputStream(frame), null));
    }

    @Test
    void dictionaryAdaptiveWindowRequiresHighWinRate() throws Exception {
        ZstdDictionary dictionary = DictionaryFixtures.dictionary();
        assertFalse(dictionary.shouldPreferDictionary());
        for (var i = 0; i < 127; i++) dictionary.recordCompressionOutcome(true);
        assertFalse(dictionary.shouldPreferDictionary());
        dictionary.recordCompressionOutcome(true);
        assertTrue(dictionary.shouldPreferDictionary());
        dictionary.recordCompressionOutcome(false);
        assertTrue(dictionary.shouldPreferDictionary());
    }
}
