package mys.zstdnet.reborn.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigTest {
    @TempDir Path directory;

    @Test
    void newConfigurationUsesProbeDrivenDefaults() throws Exception {
        var config = ClientConfig.load(directory);
        assertEquals(6, config.compressionLevel());
        assertFalse(java.nio.file.Files.readString(directory.resolve("zstdnet-client.properties"))
            .contains("enabled"));
        assertTrue(config.compressionLevel() > 0);
    }
}
