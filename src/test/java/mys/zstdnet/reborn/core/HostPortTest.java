package mys.zstdnet.reborn.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostPortTest {
    @Test
    void parsesIpv4AndDefaultPort() {
        assertEquals(new HostPort("127.0.0.1", 25565), HostPort.parse("127.0.0.1"));
        assertEquals(new HostPort("0.0.0.0", 35565), HostPort.parse("0.0.0.0:35565"));
    }

    @Test
    void parsesIpv6() {
        assertEquals(new HostPort("::1", 25565), HostPort.parse("[::1]"));
        assertEquals(new HostPort("::1", 35565), HostPort.parse("[::1]:35565"));
    }
}
