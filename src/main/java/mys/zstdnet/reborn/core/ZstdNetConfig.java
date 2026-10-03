package mys.zstdnet.reborn.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ZstdNetConfig(
    boolean enabled,
    HostPort listen,
    HostPort target,
    int compressionLevel,
    String rawLoginMessage,
    boolean requireZstdClient
) {
    public static ZstdNetConfig defaults(HostPort listen, HostPort target) {
        return new ZstdNetConfig(
            true,
            listen,
            target,
            9,
            "This server requires a ZstdNet client",
            true
        );
    }

    public static ZstdNetConfig load(Path path, HostPort listen, HostPort target) throws IOException {
        var defaults = defaults(listen, target);
        var properties = new Properties();
        if (Files.exists(path)) {
            try (var input = Files.newInputStream(path)) {
                properties.load(input);
            }
        } else {
            Files.createDirectories(path.getParent());
        }
        var config = new ZstdNetConfig(
            defaults.enabled(), listen, target, defaults.compressionLevel(),
            properties.getProperty("raw_login_message", defaults.rawLoginMessage()),
            Boolean.parseBoolean(properties.getProperty("require_zstd_client",
                Boolean.toString(defaults.requireZstdClient())))
        );
        properties.setProperty("require_zstd_client", Boolean.toString(config.requireZstdClient()));
        properties.setProperty("raw_login_message", config.rawLoginMessage());
        try (var output = Files.newOutputStream(path)) {
            properties.store(output, "ZstdNet server connection policy");
        }
        return config;
    }
}
