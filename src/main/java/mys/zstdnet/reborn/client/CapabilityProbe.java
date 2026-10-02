package mys.zstdnet.reborn.client;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

final class CapabilityProbe {
    private static final int TIMEOUT_MILLIS = 2_000;

    private CapabilityProbe() {}

    static boolean probe(String host, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream output = socket.getOutputStream();
            output.write(ZstdFrameCodec.CAPABILITY_MAGIC);
            output.flush();
            InputStream input = socket.getInputStream();
            for (byte expected : ZstdFrameCodec.CAPABILITY_RESPONSE) {
                if (input.read() != (expected & 0xFF)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
