package mys.zstdnet.reborn.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ClientConfig {
    private final int compressionLevel;

    private ClientConfig(int compressionLevel) {
        this.compressionLevel = Math.clamp(compressionLevel, 1, 22);
    }

    public static ClientConfig load(Path configDir) {
        var path = configDir.resolve("zstdnet-client.properties");
        var props = new Properties();
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        } else {
            props.setProperty("compression-level", "6");
            try {
                Files.createDirectories(configDir);
                try (var out = Files.newOutputStream(path)) {
                    props.store(out, "ZstdNet client configuration");
                }
            } catch (IOException ignored) {
            }
        }

        var level = parseInt(props.getProperty("compression-level"));
        return new ClientConfig(level);
    }

    public int compressionLevel() {
        return compressionLevel;
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 6;
        }
    }
}
