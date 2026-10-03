package mys.zstdnet.reborn.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZstdNetConnectionHooksTest {
    @Test
    void installRetryWindowMatchesProbeWindow() {
        assertEquals(ProtocolProbe.TIMEOUT_MILLIS + 500L,
            ZstdNetConnectionHooks.PROBE_WAIT_MILLIS);
    }
}
