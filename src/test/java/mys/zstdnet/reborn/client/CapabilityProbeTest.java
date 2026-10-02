package mys.zstdnet.reborn.client;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityProbeTest {
    @Test
    void cachesSuccessfulVersionedProbe() throws Exception {
        try (var server = new ServerSocket(0)) {
            var worker = respondOnce(server, ZstdFrameCodec.CAPABILITY_RESPONSE, false);
            CapabilityProbe.start("127.0.0.1", server.getLocalPort());
            assertEquals(Boolean.TRUE, awaitCached("127.0.0.1", server.getLocalPort()));
            worker.join(2_000L);
        }
    }

    @Test
    void cachesFalseForInvalidResponse() throws Exception {
        try (var server = new ServerSocket(0)) {
            var invalid = ZstdFrameCodec.CAPABILITY_RESPONSE.clone();
            invalid[invalid.length - 1] ^= 1;
            var worker = respondOnce(server, invalid, false);
            CapabilityProbe.start("127.0.0.1", server.getLocalPort());
            assertEquals(Boolean.FALSE, awaitCached("127.0.0.1", server.getLocalPort()));
            worker.join(2_000L);
        }
    }

    @Test
    void cachesFalseAfterProbeTimeout() throws Exception {
        try (var server = new ServerSocket(0)) {
            var worker = respondOnce(server, new byte[0], true);
            CapabilityProbe.start("127.0.0.1", server.getLocalPort());
            assertEquals(Boolean.FALSE, awaitCached("127.0.0.1", server.getLocalPort()));
            worker.join(2_000L);
        }
    }

    private static Thread respondOnce(ServerSocket server, byte[] response, boolean holdOpen) {
        var worker = Thread.ofPlatform().daemon().start(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2_000);
                InputStream input = socket.getInputStream();
                byte[] magic = input.readNBytes(ZstdFrameCodec.CAPABILITY_MAGIC.length);
                assertTrue(java.util.Arrays.equals(ZstdFrameCodec.CAPABILITY_MAGIC, magic));
                if (holdOpen) {
                    Thread.sleep(1_500L);
                } else {
                    OutputStream output = socket.getOutputStream();
                    output.write(response);
                    output.flush();
                }
            } catch (Exception ignored) {
                // The probe intentionally closes the socket on invalid or timed-out responses.
            }
        });
        return worker;
    }

    private static Boolean awaitCached(String host, int port) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Boolean value;
        while ((value = CapabilityProbe.cached(host, port)) == null && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        return value;
    }
}
