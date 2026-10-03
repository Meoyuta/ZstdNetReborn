package mys.zstdnet.reborn.client;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolProbeTest {
    @AfterEach
    void clearProbeState() {
        ProtocolProbe.clearForTests();
    }

    @Test
    void cachesSuccessfulVersionedProbe() throws Exception {
        try (var server = new ServerSocket(0)) {
            var worker = respondOnce(server, response(8), false);
            ProtocolProbe.start("127.0.0.1", server.getLocalPort());
            assertTrue(awaitCached("127.0.0.1", server.getLocalPort()).supported());
            assertEquals(8, awaitCached("127.0.0.1", server.getLocalPort()).serverLevel());
            worker.join(2_000L);
        }
    }

    @Test
    void cachesFalseForInvalidResponse() throws Exception {
        try (var server = new ServerSocket(0)) {
            var invalid = response(8);
            invalid[invalid.length - 2] ^= 1;
            var worker = respondOnce(server, invalid, false);
            ProtocolProbe.start("127.0.0.1", server.getLocalPort());
            assertTrue(!awaitCached("127.0.0.1", server.getLocalPort()).supported());
            worker.join(2_000L);
        }
    }

    @Test
    void cachesFalseAfterProbeTimeout() throws Exception {
        try (var server = new ServerSocket(0)) {
            var worker = respondOnce(server, new byte[0], true);
            ProtocolProbe.start("127.0.0.1", server.getLocalPort());
            assertTrue(!awaitCached("127.0.0.1", server.getLocalPort()).supported());
            worker.join(2_000L);
        }
    }

    @Test
    void rejectsLegacyFiveByteResponse() throws Exception {
        try (var server = new ServerSocket(0)) {
            var worker = respondOnce(server, ZstdFrameCodec.PROTOCOL_PROBE_RESPONSE_PREFIX, false);
            ProtocolProbe.start("127.0.0.1", server.getLocalPort());
            assertTrue(!awaitCached("127.0.0.1", server.getLocalPort()).supported());
            worker.join(2_000L);
        }
    }

    @Test
    void rejectsInvalidServerLevel() throws Exception {
        try (var server = new ServerSocket(0)) {
            var worker = respondOnce(server, response(0), false);
            ProtocolProbe.start("127.0.0.1", server.getLocalPort());
            assertTrue(!awaitCached("127.0.0.1", server.getLocalPort()).supported());
            worker.join(2_000L);
        }
    }

    @Test
    void awaitResultEnablesFirstConnectionProbeWithinBound() throws Exception {
        try (var server = new ServerSocket(0)) {
            var worker = respondOnce(server, response(12), false);
            long started = System.nanoTime();
            var result = ProtocolProbe.awaitResult("127.0.0.1", server.getLocalPort(), 300L);
            assertTrue(result.supported());
            assertEquals(12, result.serverLevel());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000L);
            worker.join(2_000L);
        }
    }

    private static Thread respondOnce(ServerSocket server, byte[] response, boolean holdOpen) {
        var worker = Thread.ofPlatform().daemon().start(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2_000);
                InputStream input = socket.getInputStream();
                byte[] magic = input.readNBytes(ZstdFrameCodec.PROTOCOL_PROBE_MAGIC.length);
                assertTrue(java.util.Arrays.equals(ZstdFrameCodec.PROTOCOL_PROBE_MAGIC, magic));
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

    private static ProtocolProbe.ProbeResult awaitCached(String host, int port) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ProtocolProbe.ProbeResult value;
        while ((value = ProtocolProbe.cached(host, port)) == null && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        return value;
    }

    private static byte[] response(int level) {
        var response = new byte[ZstdFrameCodec.PROTOCOL_PROBE_RESPONSE_BYTES];
        System.arraycopy(ZstdFrameCodec.PROTOCOL_PROBE_RESPONSE_PREFIX, 0, response, 0,
            ZstdFrameCodec.PROTOCOL_PROBE_RESPONSE_PREFIX.length);
        response[response.length - 1] = (byte) level;
        return response;
    }
}
