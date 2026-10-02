package mys.zstdnet.reborn.core;

public record ZstdNetConfig(
    boolean enabled,
    HostPort listen,
    HostPort target,
    int compressionLevel,
    String rawLoginMessage
) {
    public static ZstdNetConfig defaults(HostPort listen, HostPort target) {
        return new ZstdNetConfig(
            true,
            listen,
            target,
            9,
            "This server requires the ZstdNet client mod."
        );
    }
}
