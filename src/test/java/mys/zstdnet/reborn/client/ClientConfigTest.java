package mys.zstdnet.reborn.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ClientConfigTest {
    @TempDir Path directory;

    @Test
    void newConfigurationDoesNotEnableEveryServer() {
        var config = ClientConfig.load(directory);
        assertFalse(config.enabledFor("example.invalid", 25565));
    }
}
