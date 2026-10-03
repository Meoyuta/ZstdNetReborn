package mys.zstdnet.reborn.client;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolProbeTest {
    @Test
    void waitsForSlowServerResponseWithinNegotiationWindow() throws Exception {
        try (var server = new ServerSocket(0)) {
            var ready = new CountDownLatch(1);
            var thread = new Thread(() -> {
                try (var socket = server.accept()) {
                    ready.countDown();
                    socket.getInputStream().readNBytes(4);
                    Thread.sleep(500L);
                    OutputStream output = socket.getOutputStream();
                     output.write(new byte[]{'Z', 'N', 'P', 0x02, 0x02, 0x06});
                    output.flush();
                } catch (Exception ignored) {
                }
            });
            thread.start();
            var result = ProtocolProbe.awaitResult("127.0.0.1", server.getLocalPort(), 3500L);
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            assertTrue(result.supported());
            assertEquals(6, result.serverLevel());
            thread.join(2000L);
        }
    }

    @Test
    void probeTimeoutIsLongerThanOriginalShortWait() {
        assertTrue(ProtocolProbe.TIMEOUT_MILLIS >= 3000);
        assertTrue(ZstdNetConnectionHooks.PROBE_WAIT_MILLIS >= ProtocolProbe.TIMEOUT_MILLIS);
    }

    @Test
    void ipv6HostFormsShareProbeCacheKey() {
        assertEquals(ProtocolProbe.normalizeHost("[::1]"), ProtocolProbe.normalizeHost("::1"));
        assertEquals(ProtocolProbe.normalizeHost("[2001:DB8::1]"), ProtocolProbe.normalizeHost("2001:db8::1"));
    }
}
