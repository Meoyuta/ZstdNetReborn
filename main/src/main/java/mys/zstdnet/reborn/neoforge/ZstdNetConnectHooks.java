package mys.zstdnet.reborn.neoforge;

import mys.zstdnet.reborn.client.ZstdNetConnectionHooks;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class ZstdNetConnectHooks {
    private ZstdNetConnectHooks() {
    }

    public static ServerAddress intercept(ServerAddress original) {
        if (original == null) {
            return null;
        }

        ZstdNetConnectionHooks.prepare(original.getHost(), original.getPort());
        return original;
    }
}
