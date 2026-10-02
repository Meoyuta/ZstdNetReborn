package mys.zstdnet.reborn.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ClientConfig {
    private final boolean enabled;
    private final int compressionLevel;

    private ClientConfig(boolean enabled, int compressionLevel) {
        this.enabled = enabled;
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
            // Compression remains opt-in globally; every enabled server is
            // checked by the capability probe before the pipeline is installed.
            props.setProperty("enabled", "false");
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
        return new ClientConfig(Boolean.parseBoolean(props.getProperty("enabled", "false")), level);
    }

    public boolean enabledFor(String host, int port) {
        return enabled && host != null && !host.isBlank();
    }

    public boolean enabled() { return enabled; }

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
