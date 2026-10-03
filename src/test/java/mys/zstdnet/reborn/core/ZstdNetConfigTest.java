package mys.zstdnet.reborn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ZstdNetConfigTest {
    @TempDir Path directory;

    @Test
    void defaultsRequireZstdClientAndPersistPolicy() throws Exception {
        var path = directory.resolve("server.properties");
        var config = ZstdNetConfig.load(path, new HostPort("0.0.0.0", 25565), new HostPort("same-port", 25565));
        assertTrue(config.requireZstdClient());
        assertTrue(java.nio.file.Files.readString(path).contains("require_zstd_client=true"));
    }

    @Test
    void explicitPolicyAllowsRawFallback() throws Exception {
        var path = directory.resolve("server.properties");
        java.nio.file.Files.writeString(path, "require_zstd_client=false\nraw_login_message=custom\n");
        var config = ZstdNetConfig.load(path, new HostPort("0.0.0.0", 25565), new HostPort("same-port", 25565));
        assertFalse(config.requireZstdClient());
        assertEquals("custom", config.rawLoginMessage());
    }
}
